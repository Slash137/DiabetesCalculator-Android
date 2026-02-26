package com.diabetes.calculator.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.diabetes.calculator.BuildConfig
import com.diabetes.calculator.data.database.AppDatabase
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncChannel
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncOperation
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncStatus
import com.diabetes.calculator.data.entity.LibreviewRepairPhase
import com.diabetes.calculator.data.entity.LibreviewRepairRunStatus
import com.diabetes.calculator.data.repository.LibreviewRegistrosSyncService
import com.diabetes.calculator.data.repository.LibreviewRepository
import com.diabetes.calculator.data.repository.LibreviewKnownRecordNumber
import com.diabetes.calculator.data.repository.LibreviewRepairSnapshot
import com.diabetes.calculator.data.repository.LibreviewRepairRunRepository
import com.diabetes.calculator.data.repository.LibreviewRecordCatalogRepository
import com.diabetes.calculator.data.repository.LibreviewSyncRunResult
import com.diabetes.calculator.data.repository.LibreviewWipePlan
import com.diabetes.calculator.data.repository.NightscoutRegistrosSyncService
import com.diabetes.calculator.data.repository.NightscoutRepository
import com.diabetes.calculator.data.repository.NightscoutTreatmentTombstoneRepository
import com.diabetes.calculator.data.repository.RegistroComidaRepository
import com.diabetes.calculator.data.repository.RegistroLibreviewSyncRepository
import com.diabetes.calculator.data.repository.RegistroNightscoutSyncRepository
import com.diabetes.calculator.data.repository.UsuarioProfileRepository
import com.diabetes.calculator.domain.SyncLinkTolerance
import com.diabetes.calculator.util.LibreviewSecretStore
import com.diabetes.calculator.util.NightscoutRetryPolicy
import com.diabetes.calculator.util.NightscoutTokenStore
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LibreviewSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return syncMutex.withLock {
            val executionMode = LibreviewExecutionMode.fromValue(
                inputData.getString(KEY_EXECUTION_MODE)
            )
            val requestedWipeRound = inputData.getInt(KEY_WIPE_ROUND, 1).coerceAtLeast(1)
            val isRepairMode = executionMode != LibreviewExecutionMode.NORMAL
            if (!isRepairMode && repairInProgress) {
                return@withLock Result.success()
            }
            if (isRepairMode) {
                repairInProgress = true
            }
            var keepRepairInProgress = false

            try {
                val database = AppDatabase.getDatabase(applicationContext)
                val tokenStore = NightscoutTokenStore(applicationContext)
                val libreviewSecretStore = LibreviewSecretStore(applicationContext)
                val queueRepository = RegistroLibreviewSyncRepository(database.registroLibreviewSyncDao())
                val catalogRepository = LibreviewRecordCatalogRepository(
                    database.libreviewRecordCatalogDao()
                )
                val repairRunRepository = LibreviewRepairRunRepository(
                    database.libreviewRepairRunDao()
                )
                if (!isRepairMode && repairRunRepository.getLatestActiveRun() != null) {
                    return@withLock Result.success()
                }
                val profileRepository = UsuarioProfileRepository(
                    dao = database.usuarioProfileDao(),
                    tokenStore = tokenStore,
                    libreviewSecretStore = libreviewSecretStore
                )
                val profile = profileRepository.getProfileSync() ?: return@withLock Result.success()
                val forceManual = inputData.getBoolean(KEY_FORCE_MANUAL, false) || isRepairMode
                if (!forceManual && !profile.libreviewSyncActivo) return@withLock Result.success()

                val startedAt = System.currentTimeMillis()
                if (executionMode == LibreviewExecutionMode.NORMAL && !forceManual) {
                    val lastSyncAt = libreviewSecretStore.getLastSyncAt() ?: 0L
                    if (startedAt - lastSyncAt < SYNC_COOLDOWN_MILLIS) {
                        return@withLock Result.success()
                    }
                    libreviewSecretStore.setLastSyncAt(startedAt)
                }

                val inputEmail = inputData.getString(KEY_EMAIL_OVERRIDE)?.trim().orEmpty()
                val inputPassword = inputData.getString(KEY_PASSWORD_OVERRIDE)?.trim().orEmpty()
                val storedEmail = libreviewSecretStore.getEmail()?.trim().orEmpty()
                val storedPassword = libreviewSecretStore.getPassword()?.trim().orEmpty()
                val appStableSerial = libreviewSecretStore.getOrCreateAppStableSerial()
                val email = if (inputEmail.isNotBlank()) inputEmail else storedEmail
                val password = if (inputPassword.isNotBlank()) inputPassword else storedPassword
                if (email.isNotBlank() && password.isNotBlank() &&
                    (email != storedEmail || password != storedPassword)
                ) {
                    libreviewSecretStore.setCredentials(email = email, password = password)
                }

                val libreviewRepository = LibreviewRepository(
                    diagnosticsLogger = if (BuildConfig.DEBUG) {
                        { diag ->
                            Log.d(
                                TAG,
                                "LV_HTTP method=${diag.method} path=${diag.path} status=${diag.status} " +
                                    "size=${diag.responseSize} parsed=${diag.parsedEntries} note=${diag.note.orEmpty()}"
                            )
                        }
                    } else {
                        null
                    }
                )
                val registroRepository = RegistroComidaRepository(database.registroComidaDao())
                val profileLinkMinutes = profile.nightscoutLinkOffsetMinutes.coerceIn(0, 180)
                val profileLinkUnits = profile.nightscoutLinkOffsetUnits.coerceIn(0f, 5f)
                val globalLinkMinutes = max(
                    profileLinkMinutes,
                    SyncLinkTolerance.WINDOW_MINUTES
                )
                val globalLinkUnits = max(
                    profileLinkUnits,
                    SyncLinkTolerance.WINDOW_UNITS
                )
                val globalLinkMillis = globalLinkMinutes * 60_000L
                val nightscoutMergeService = NightscoutRegistrosSyncService(
                    registroRepository = registroRepository,
                    queueRepository = RegistroNightscoutSyncRepository(database.registroNightscoutSyncDao()),
                    tombstoneRepository = NightscoutTreatmentTombstoneRepository(
                        database.nightscoutTreatmentTombstoneDao()
                    ),
                    nightscoutRepository = NightscoutRepository(),
                    libreviewQueueRepository = queueRepository
                )
                val service = LibreviewRegistrosSyncService(
                    registroRepository = registroRepository,
                    queueRepository = queueRepository,
                    libreviewRepository = libreviewRepository,
                    linkMatchDeltaMillis = globalLinkMillis,
                    linkMatchInsulinDelta = globalLinkUnits,
                    appStableSerial = appStableSerial,
                    recordCatalogRepository = catalogRepository,
                    repairRunRepository = repairRunRepository
                )

                val now = System.currentTimeMillis()
                val dedupeMinutes = if (isRepairMode) profileLinkMinutes else globalLinkMinutes
                val dedupeUnits = if (isRepairMode) profileLinkUnits else globalLinkUnits
                if (!isRepairMode) {
                    nightscoutMergeService.reconcileLocalDuplicatesOnly(
                        now = now,
                        linkOffsetMinutes = dedupeMinutes,
                        linkOffsetUnits = dedupeUnits
                    )
                }
                if (forceManual && !isRepairMode) {
                    // Manual sync starts from a clean slate to avoid draining stale repair queues.
                    queueRepository.deleteAll()
                    val manualInsulinScopeStart = computeManualInsulinScopeFromMillis(now)
                    service.enqueueMissingCanonicalForManualSync(
                        linkOffsetMinutes = profileLinkMinutes,
                        linkOffsetUnits = profileLinkUnits,
                        nfcInsulinOnly = false,
                        minCarbsEventTimestampMillis = null,
                        minInsulinEventTimestampMillis = manualInsulinScopeStart,
                        forceReuploadCarbs = true,
                        forceReuploadInsulin = true,
                        now = now
                    )
                }

                if (email.isBlank() || password.isBlank()) {
                    markPendingAsFailed(
                        queueRepository = queueRepository,
                        error = "Credenciales LibreView vacías",
                        now = startedAt
                    )
                    if (isRepairMode) {
                        val repairRunId = inputData.getLong(KEY_REPAIR_RUN_ID, -1L)
                            .takeIf { it > 0L }
                            ?: repairRunRepository.getLatestActiveRun()?.id
                        if (repairRunId != null) {
                            repairRunRepository.markFailed(
                                runId = repairRunId,
                                reason = "Credenciales LibreView vacías",
                                reportJson = null,
                                now = startedAt
                            )
                        }
                    }
                    return@withLock Result.success()
                }

                val authenticatedSession = authenticateLibreviewSession(
                    libreviewRepository = libreviewRepository,
                    libreviewSecretStore = libreviewSecretStore,
                    regionOverride = profile.libreviewRegionOverride,
                    email = email,
                    password = password
                )
                var session = authenticatedSession ?: run {
                    val authError =
                        libreviewRepository.lastErrorMessage ?: "No se pudo autenticar con LibreView"
                    if (!isWrongDeviceError(authError) && !isDnsResolutionError(authError)) {
                        markPendingAsFailed(
                            queueRepository = queueRepository,
                            error = authError,
                            now = startedAt
                        )
                    } else {
                        Log.w(
                            TAG,
                            "LV auth recuperable tras reintento automático; se reprograma desde fase actual"
                        )
                    }
                    if (isRepairMode) {
                        keepRepairInProgress = true
                        val repairRunId = inputData.getLong(KEY_REPAIR_RUN_ID, -1L)
                            .takeIf { it > 0L }
                            ?: repairRunRepository.getLatestActiveRun()?.id
                        enqueueRepairPhaseSeconds(
                            workManager = WorkManager.getInstance(applicationContext),
                            mode = executionMode,
                            runId = repairRunId,
                            delaySeconds = 10,
                            wipeRound = requestedWipeRound
                        )
                    } else {
                        enqueueRetry(
                            WorkManager.getInstance(applicationContext),
                            NightscoutRetryPolicy.nextDelayMinutes(1)
                        )
                    }
                    return@withLock Result.success()
                }

                if (isRepairMode) {
                    val oldestUploadableTimestamp = registroRepository.getOldestUploadableTimestamp() ?: now
                    val repairScopeFromMillis = computeRepairScopeFromMillis(now = now)
                    val localRepairScopeStart = max(
                        oldestUploadableTimestamp.coerceAtLeast(0L),
                        repairScopeFromMillis
                    ).coerceAtMost(now)
                    val repairRangeStart = (localRepairScopeStart - globalLinkMillis).coerceAtLeast(0L)
                    val repairRangeEnd = now
                    val runIdInput = inputData.getLong(KEY_REPAIR_RUN_ID, -1L).takeIf { it > 0L }

                    when (executionMode) {
                        LibreviewExecutionMode.REPAIR_RESET,
                        LibreviewExecutionMode.REPAIR_DISCOVERY -> {
                            // Ensure repair discovery starts from a clean queue so wipe runs first.
                            queueRepository.deleteAll()
                            val snapshot = service.buildLocalRepairSnapshot(
                                repairLinkOffsetMinutes = profileLinkMinutes,
                                repairLinkOffsetUnits = profileLinkUnits,
                                minEventTimestampMillis = localRepairScopeStart,
                                now = now
                            )
                            val runId = repairRunRepository.startRun(
                                canonicalRecords = snapshot.canonicalRecords,
                                snapshotJson = service.encodeRepairSnapshot(snapshot),
                                now = now
                            )
                            repairRunRepository.markPhase(
                                runId = runId,
                                phase = LibreviewRepairPhase.DISCOVERY,
                                status = LibreviewRepairRunStatus.IN_PROGRESS,
                                now = now
                            )
                            val probe = libreviewRepository.probeMeasurementsReadEndpoint(
                                session = session,
                                fromMillis = repairRangeStart,
                                toMillis = repairRangeEnd
                            )
                            val wipePlan = if (probe.success) {
                                service.buildRemoteAggressiveWipePlan(
                                    snapshot = snapshot,
                                    session = session,
                                    fromMillis = repairRangeStart,
                                    toMillis = repairRangeEnd
                                )
                            } else {
                                service.buildBlindWipePlan(snapshot = snapshot)
                            }
                            val snapshotWithSerialless = mergeSnapshotSeriallessKeys(
                                snapshot = snapshot,
                                wipePlan = wipePlan
                            )
                            val nightscoutManagedDeleteKeys = snapshot.nightscoutManagedDeleteOps
                                .map { it.channel to it.recordNumber }
                                .toSet()
                            val seriallessManagedDeleteKeys = snapshotWithSerialless.seriallessKnownRecordNumbers
                                .map { it.channel to it.recordNumber }
                                .toSet()
                            val repairModeLabel = if (probe.success) "remote_read" else "blind_wipe"
                            val wipeRoundsTarget = if (probe.success) {
                                REMOTE_WIPE_MAX_ROUNDS
                            } else {
                                BLIND_WIPE_ROUNDS
                            }
                            val seriallessDetectedRemote = if (probe.success) {
                                wipePlan.seriallessOverlap.size
                            } else {
                                0
                            }
                            val overlapDeletesPlanned = if (probe.success) {
                                wipePlan.unknownOverlap.size + seriallessDetectedRemote
                            } else {
                                0
                            }
                            val knownManagedCount = if (probe.success) {
                                (wipePlan.knownAppManaged.size - overlapDeletesPlanned)
                                    .coerceAtLeast(0)
                            } else {
                                wipePlan.knownAppManaged.size
                            }
                            val deletePlannedOps = service.countWipeDeleteOps(
                                wipePlan = wipePlan,
                                minRepeatsPerKey = WIPE_MIN_REPEATS_ROUND_ONE,
                                maxRepeatsPerKey = WIPE_MAX_REPEATS_PER_KEY
                            )
                            val nightscoutDeletePlannedOps = service.countWipeDeleteOpsForKnownKeys(
                                wipePlan = wipePlan,
                                keys = nightscoutManagedDeleteKeys,
                                minRepeatsPerKey = WIPE_MIN_REPEATS_ROUND_ONE,
                                maxRepeatsPerKey = WIPE_MAX_REPEATS_PER_KEY
                            )
                            val seriallessDeletePlannedOps = service.countWipeDeleteOpsForKnownKeys(
                                wipePlan = wipePlan,
                                keys = seriallessManagedDeleteKeys,
                                minRepeatsPerKey = WIPE_MIN_REPEATS_ROUND_ONE,
                                maxRepeatsPerKey = WIPE_MAX_REPEATS_PER_KEY
                            )
                            val discoveryReport = if (probe.success) {
                                buildRepairReport(
                                    mode = repairModeLabel,
                                    wipeRoundActual = 1,
                                    wipeRoundsTarget = wipeRoundsTarget,
                                    probeEndpoint = probe.endpointId.orEmpty(),
                                    nightscoutImportSkippedUpserts = snapshot.nightscoutImportSkippedUpserts,
                                    nightscoutManagedDeletePlanned = nightscoutDeletePlannedOps,
                                    nightscoutManagedDeleteSucceeded = 0,
                                    seriallessDetectedRemote = seriallessDetectedRemote,
                                    seriallessDeletePlanned = seriallessDeletePlannedOps,
                                    seriallessDeleteSucceeded = 0,
                                    repairScopeFromDate = REPAIR_SCOPE_FROM_CONTEXT_DATE,
                                    cutoffContextDate = CUTOFF_CONTEXT_DATE
                                )
                            } else {
                                buildRepairReport(
                                    mode = repairModeLabel,
                                    wipeRoundActual = 1,
                                    wipeRoundsTarget = wipeRoundsTarget,
                                    probeFailed = probe.reason.orEmpty(),
                                    nightscoutImportSkippedUpserts = snapshot.nightscoutImportSkippedUpserts,
                                    nightscoutManagedDeletePlanned = nightscoutDeletePlannedOps,
                                    nightscoutManagedDeleteSucceeded = 0,
                                    seriallessDetectedRemote = seriallessDetectedRemote,
                                    seriallessDeletePlanned = seriallessDeletePlannedOps,
                                    seriallessDeleteSucceeded = 0,
                                    repairScopeFromDate = REPAIR_SCOPE_FROM_CONTEXT_DATE,
                                    cutoffContextDate = CUTOFF_CONTEXT_DATE
                                )
                            }
                            repairRunRepository.updateRun(runId) { current ->
                                current.copy(
                                    knownManagedCount = knownManagedCount,
                                    unknownOverlapCount = wipePlan.unknownOverlap.size + seriallessDetectedRemote,
                                    foreignCount = wipePlan.foreign.size,
                                    deletePlanned = deletePlannedOps,
                                    snapshotJson = service.encodeRepairSnapshot(snapshotWithSerialless),
                                    reportJson = buildRepairReport(
                                        existing = discoveryReport,
                                        overlapDeletesPlanned = overlapDeletesPlanned
                                    ),
                                    updatedAt = now
                                )
                            }
                            service.enqueueRepairWipeOnly(
                                wipePlan = wipePlan,
                                minRepeatsPerKey = WIPE_MIN_REPEATS_ROUND_ONE,
                                maxRepeatsPerKey = WIPE_MAX_REPEATS_PER_KEY,
                                now = now
                            )
                            keepRepairInProgress = true
                            enqueueRepairPhaseSeconds(
                                workManager = WorkManager.getInstance(applicationContext),
                                mode = LibreviewExecutionMode.REPAIR_WIPE_ONLY,
                                runId = runId,
                                delaySeconds = 1,
                                wipeRound = 1
                            )
                        }

                        LibreviewExecutionMode.REPAIR_RETRY_CHAIN,
                        LibreviewExecutionMode.REPAIR_WIPE_ONLY -> {
                            val wipePhase = runRepairWipePhase(
                                runIdInput = runIdInput,
                                requestedWipeRound = requestedWipeRound,
                                queueRepository = queueRepository,
                                repairRunRepository = repairRunRepository,
                                service = service,
                                libreviewRepository = libreviewRepository,
                                libreviewSecretStore = libreviewSecretStore,
                                profile = profile,
                                session = session,
                                email = email,
                                password = password,
                                repairRangeStart = repairRangeStart,
                                repairRangeEnd = repairRangeEnd,
                                now = now
                            )
                            session = wipePhase.session
                            keepRepairInProgress = keepRepairInProgress || wipePhase.keepRepairInProgress
                        }

                        LibreviewExecutionMode.REPAIR_UPSERT_ONLY -> {
                            val runId = runIdInput ?: repairRunRepository.getLatestActiveRun()?.id
                            if (runId == null) return@withLock Result.success()
                            // Hard guard: upsert phase must not carry leftover DELETE operations.
                            queueRepository.deleteByOperation(RegistroLibreviewSyncOperation.DELETE)
                            val run = repairRunRepository.getRun(runId) ?: return@withLock Result.success()
                            val runStartedAt = run.startedAt
                            repairRunRepository.markPhase(
                                runId = runId,
                                phase = LibreviewRepairPhase.UPSERT_ONLY,
                                status = LibreviewRepairRunStatus.IN_PROGRESS,
                                now = now
                            )
                            val hasQueueBefore = queueRepository.getPendingOrFailed().isNotEmpty()
                            if (!hasQueueBefore) {
                                // Strict sequence for repair:
                                // 1) wipe rounds remote, 2) local canonicalization, 3) canonical upsert.
                                nightscoutMergeService.reconcileLocalDuplicatesOnly(
                                    now = now,
                                    linkOffsetMinutes = profileLinkMinutes,
                                    linkOffsetUnits = profileLinkUnits
                                )
                                val rebuiltSnapshot = service.buildLocalRepairSnapshot(
                                    repairLinkOffsetMinutes = profileLinkMinutes,
                                    repairLinkOffsetUnits = profileLinkUnits,
                                    minEventTimestampMillis = localRepairScopeStart,
                                    now = now
                                )
                                service.enqueueRepairUpsertOnly(
                                    snapshot = rebuiltSnapshot,
                                    now = now
                                )
                                repairRunRepository.updateRun(runId) { current ->
                                    current.copy(
                                        snapshotJson = service.encodeRepairSnapshot(rebuiltSnapshot),
                                        canonicalRecords = rebuiltSnapshot.canonicalRecords,
                                        upsertPlanned = rebuiltSnapshot.upsertOps.size,
                                        updatedAt = now
                                    )
                                }
                            } else if (service.decodeRepairSnapshot(run.snapshotJson) == null) {
                                repairRunRepository.markFailed(
                                    runId = runId,
                                    reason = "Snapshot de reparación inválido",
                                    reportJson = run.reportJson,
                                    now = now
                                )
                                return@withLock Result.success()
                            }
                            val upsertSync = runSyncWithAutoDeviceRecovery(
                                service = service,
                                libreviewRepository = libreviewRepository,
                                libreviewSecretStore = libreviewSecretStore,
                                regionOverride = profile.libreviewRegionOverride,
                                email = email,
                                password = password,
                                profileForSync = profile.copy(libreviewSyncActivo = true),
                                session = session,
                                bypassFailureBackoff = true,
                                prioritizeDeleteOperations = false,
                                repairMode = true,
                                allowPendingInsulin = false,
                                now = now,
                                contextLabel = "repair_upsert"
                            )
                            session = upsertSync.session
                            if (upsertSync.unrecoverableWrongDevice) {
                                keepRepairInProgress = true
                                enqueueRepairPhaseSeconds(
                                    workManager = WorkManager.getInstance(applicationContext),
                                    mode = LibreviewExecutionMode.REPAIR_UPSERT_ONLY,
                                    runId = runId,
                                    delaySeconds = 10
                                )
                                return@withLock Result.success()
                            }
                            val runResult = upsertSync.runResult
                            val remaining = queueRepository.getPendingOrFailed().isNotEmpty()
                            val upsertSucceeded = queueRepository.countByOperationAndStatusSince(
                                operation = RegistroLibreviewSyncOperation.UPSERT,
                                status = RegistroLibreviewSyncStatus.SYNCED_UPLOAD,
                                sinceMillis = runStartedAt
                            )
                            val upsertFailed = queueRepository.countByOperationAndStatusSince(
                                operation = RegistroLibreviewSyncOperation.UPSERT,
                                status = RegistroLibreviewSyncStatus.FAILED,
                                sinceMillis = runStartedAt
                            )
                            repairRunRepository.updateRun(runId) { current ->
                                current.copy(
                                    upsertSucceeded = upsertSucceeded.coerceAtMost(current.upsertPlanned),
                                    upsertFailed = upsertFailed,
                                    updatedAt = now
                                )
                            }
                            if (runResult.abortedByConsecutiveErrors || remaining) {
                                keepRepairInProgress = true
                                enqueueRepairPhaseSeconds(
                                    workManager = WorkManager.getInstance(applicationContext),
                                    mode = LibreviewExecutionMode.REPAIR_UPSERT_ONLY,
                                    runId = runId,
                                    delaySeconds = 10
                                )
                            } else {
                                keepRepairInProgress = true
                                enqueueRepairPhaseSeconds(
                                    workManager = WorkManager.getInstance(applicationContext),
                                    mode = LibreviewExecutionMode.REPAIR_VERIFY,
                                    runId = runId,
                                    delaySeconds = 1
                                )
                            }
                        }

                        LibreviewExecutionMode.REPAIR_UPSERT_PARTIAL_MANUAL -> {
                            val partialPhase = runManualPartialUpsertPhase(
                                runIdInput = runIdInput,
                                queueRepository = queueRepository,
                                repairRunRepository = repairRunRepository,
                                service = service,
                                nightscoutMergeService = nightscoutMergeService,
                                libreviewRepository = libreviewRepository,
                                libreviewSecretStore = libreviewSecretStore,
                                profile = profile,
                                profileLinkMinutes = profileLinkMinutes,
                                profileLinkUnits = profileLinkUnits,
                                localRepairScopeStart = localRepairScopeStart,
                                repairRangeStart = repairRangeStart,
                                repairRangeEnd = repairRangeEnd,
                                email = email,
                                password = password,
                                session = session,
                                now = now
                            )
                            session = partialPhase.session
                            keepRepairInProgress = keepRepairInProgress || partialPhase.keepRepairInProgress
                        }

                        LibreviewExecutionMode.REPAIR_VERIFY -> {
                            val runId = runIdInput ?: repairRunRepository.getLatestActiveRun()?.id
                            if (runId == null) return@withLock Result.success()
                            val run = repairRunRepository.getRun(runId) ?: return@withLock Result.success()
                            val snapshot = service.decodeRepairSnapshot(run.snapshotJson)
                                ?: run {
                                    repairRunRepository.markFailed(
                                        runId = runId,
                                        reason = "Snapshot de verificación inválido",
                                        reportJson = null,
                                        now = now
                                    )
                                    return@withLock Result.success()
                                }
                            repairRunRepository.markPhase(
                                runId = runId,
                                phase = LibreviewRepairPhase.VERIFY,
                                status = LibreviewRepairRunStatus.IN_PROGRESS,
                                now = now
                            )
                            val verifyProbe = libreviewRepository.probeMeasurementsReadEndpoint(
                                session = session,
                                fromMillis = repairRangeStart,
                                toMillis = repairRangeEnd
                            )
                            val verifyReport = parseRepairReport(run.reportJson)
                            val blindMode = verifyReport["mode"] == "blind_wipe"
                            val manualPartialAttempted = verifyReport["upsertPartialPlanned"] != null
                            if (!verifyProbe.success) {
                                if (blindMode && !manualPartialAttempted) {
                                    val report = buildString {
                                        append(run.reportJson.orEmpty())
                                        if (isNotBlank()) append(";")
                                        append("verifySkipped=true;reason=no_read_endpoint")
                                    }
                                    repairRunRepository.markCompleted(
                                        runId = runId,
                                        reportJson = report,
                                        now = now
                                    )
                                    profileRepository.updateLibreviewBackfillDoneAt(profile.id, now)
                                } else {
                                    repairRunRepository.markFailed(
                                        runId = runId,
                                        reason = verifyProbe.reason ?: "No se pudo verificar por falta de endpoint de lectura",
                                        reportJson = run.reportJson,
                                        now = now
                                    )
                                }
                                return@withLock Result.success()
                            }
                            val verification = service.verifyRepairOutcome(
                                snapshot = snapshot,
                                session = session,
                                fromMillis = repairRangeStart,
                                toMillis = repairRangeEnd
                            )
                            val report = buildString {
                                append(run.reportJson.orEmpty())
                                if (isNotBlank()) append(";")
                                append("missingCanonical=${verification.missingCanonical};")
                                append("managedDuplicates=${verification.managedDuplicates};")
                                append("unknownOverlap=${verification.unknownOverlap};")
                                append("seriallessOverlap=${verification.seriallessOverlap};")
                                append("foreign=${verification.foreign}")
                            }
                            if (
                                verification.missingCanonical > 0 ||
                                verification.managedDuplicates > 0 ||
                                verification.unknownOverlap > 0 ||
                                verification.seriallessOverlap > 0
                            ) {
                                repairRunRepository.markFailed(
                                    runId = runId,
                                    reason = "Verificación final con incidencias",
                                    reportJson = report,
                                    now = now
                                )
                            } else {
                                repairRunRepository.markCompleted(
                                    runId = runId,
                                    reportJson = report,
                                    now = now
                                )
                                profileRepository.updateLibreviewBackfillDoneAt(profile.id, now)
                            }
                        }

                        LibreviewExecutionMode.NORMAL -> Unit
                    }
                    return@withLock Result.success()
                }

                val hasQueuedWork = queueRepository.getPendingOrFailed().isNotEmpty()
                if (!hasQueuedWork && profile.libreviewBackfillDoneAt == null) {
                    val oldestPendingTimestamp = registroRepository.getOldestPendingLibreviewTimestamp()
                    if (oldestPendingTimestamp != null) {
                        service.enqueueBackfill(fromMillis = oldestPendingTimestamp, toMillis = now, now = now)
                    }
                    profileRepository.updateLibreviewBackfillDoneAt(profile.id, now)
                }

                val normalSync = runSyncWithAutoDeviceRecovery(
                    service = service,
                    libreviewRepository = libreviewRepository,
                    libreviewSecretStore = libreviewSecretStore,
                    regionOverride = profile.libreviewRegionOverride,
                    email = email,
                    password = password,
                    profileForSync = profile,
                    session = session,
                    bypassFailureBackoff = forceManual,
                    prioritizeDeleteOperations = false,
                    repairMode = false,
                    allowPendingInsulin = forceManual,
                    now = now,
                    contextLabel = "normal_sync"
                )
                session = normalSync.session
                if (normalSync.unrecoverableWrongDevice) {
                    enqueueRetry(
                        WorkManager.getInstance(applicationContext),
                        NightscoutRetryPolicy.nextDelayMinutes(1)
                    )
                    return@withLock Result.success()
                }
                val runResult = normalSync.runResult
                if (runResult.failedPending > 0) {
                    val delayMinutes = NightscoutRetryPolicy.nextDelayMinutes(runResult.maxFailedAttempts)
                    enqueueRetry(WorkManager.getInstance(applicationContext), delayMinutes)
                }

                Result.success()
            } finally {
                if (isRepairMode && !keepRepairInProgress) {
                    repairInProgress = false
                }
            }
        }
    }

    private data class SyncWithAutoRecoveryResult(
        val runResult: LibreviewSyncRunResult,
        val session: com.diabetes.calculator.data.model.LibreviewSession,
        val unrecoverableWrongDevice: Boolean
    )

    private data class ManualPartialPhaseResult(
        val session: com.diabetes.calculator.data.model.LibreviewSession,
        val keepRepairInProgress: Boolean
    )

    private data class WipePhaseResult(
        val session: com.diabetes.calculator.data.model.LibreviewSession,
        val keepRepairInProgress: Boolean
    )

    private suspend fun runRepairWipePhase(
        runIdInput: Long?,
        requestedWipeRound: Int,
        queueRepository: RegistroLibreviewSyncRepository,
        repairRunRepository: LibreviewRepairRunRepository,
        service: LibreviewRegistrosSyncService,
        libreviewRepository: LibreviewRepository,
        libreviewSecretStore: LibreviewSecretStore,
        profile: com.diabetes.calculator.data.entity.UsuarioProfile,
        session: com.diabetes.calculator.data.model.LibreviewSession,
        email: String,
        password: String,
        repairRangeStart: Long,
        repairRangeEnd: Long,
        now: Long
    ): WipePhaseResult {
        val runId = runIdInput ?: repairRunRepository.getLatestActiveRun()?.id
        if (runId == null) {
            return WipePhaseResult(
                session = session,
                keepRepairInProgress = false
            )
        }
        queueRepository.deleteByOperation(RegistroLibreviewSyncOperation.UPSERT)
        val run = repairRunRepository.getRun(runId)
            ?: return WipePhaseResult(
                session = session,
                keepRepairInProgress = false
            )
        val runStartedAt = run.startedAt
        var runSnapshot = service.decodeRepairSnapshot(run.snapshotJson)
        val nightscoutManagedDeleteKeys = runSnapshot
            ?.nightscoutManagedDeleteOps
            ?.map { it.channel to it.recordNumber }
            ?.toSet()
            .orEmpty()
        var seriallessManagedDeleteKeys = runSnapshot
            ?.seriallessKnownRecordNumbers
            ?.map { it.channel to it.recordNumber }
            ?.toSet()
            .orEmpty()
        val wipeRound = requestedWipeRound
        val repairModeLabel = parseRepairReport(run.reportJson)["mode"]
            ?: if (run.reportJson?.contains("mode=blind_wipe") == true) {
                "blind_wipe"
            } else {
                "remote_read"
            }
        val wipeRoundsTarget = if (repairModeLabel == "remote_read") {
            REMOTE_WIPE_MAX_ROUNDS
        } else {
            BLIND_WIPE_ROUNDS
        }
        repairRunRepository.markPhase(
            runId = runId,
            phase = LibreviewRepairPhase.WIPE_ONLY,
            status = LibreviewRepairRunStatus.IN_PROGRESS,
            now = now
        )
        val wipeSync = runSyncWithAutoDeviceRecovery(
            service = service,
            libreviewRepository = libreviewRepository,
            libreviewSecretStore = libreviewSecretStore,
            regionOverride = profile.libreviewRegionOverride,
            email = email,
            password = password,
            profileForSync = profile.copy(libreviewSyncActivo = true),
            session = session,
            bypassFailureBackoff = true,
            prioritizeDeleteOperations = true,
            repairMode = true,
            allowPendingInsulin = false,
            now = now,
            contextLabel = "repair_wipe_round_$wipeRound"
        )
        val refreshedSession = wipeSync.session
        if (wipeSync.unrecoverableWrongDevice) {
            val existingSeriallessDeleteSucceeded = parseRepairReport(run.reportJson)[
                "seriallessDeleteSucceeded"
            ]?.toIntOrNull() ?: 0
            repairRunRepository.updateRun(runId) { current ->
                current.copy(
                    reportJson = buildRepairReport(
                        existing = current.reportJson,
                        mode = repairModeLabel,
                        wipeRoundActual = wipeRound,
                        wipeRoundsTarget = wipeRoundsTarget,
                        nightscoutManagedDeleteSucceeded = 0,
                        seriallessDeleteSucceeded = existingSeriallessDeleteSucceeded
                    ),
                    updatedAt = now
                )
            }
            enqueueRepairPhaseSeconds(
                workManager = WorkManager.getInstance(applicationContext),
                mode = LibreviewExecutionMode.REPAIR_WIPE_ONLY,
                runId = runId,
                delaySeconds = 10,
                wipeRound = wipeRound
            )
            return WipePhaseResult(
                session = refreshedSession,
                keepRepairInProgress = true
            )
        }

        val runResult = wipeSync.runResult
        val hasRemainingRepairQueue = queueRepository
            .getPendingOrFailedPrioritizingDeletes()
            .isNotEmpty()
        val deleteSucceeded = queueRepository.countByOperationAndStatusSince(
            operation = RegistroLibreviewSyncOperation.DELETE,
            status = RegistroLibreviewSyncStatus.SYNCED_UPLOAD,
            sinceMillis = runStartedAt
        )
        val deleteFailedTolerated = queueRepository.countByOperationAndStatusSince(
            operation = RegistroLibreviewSyncOperation.DELETE,
            status = RegistroLibreviewSyncStatus.SYNCED_NO_UPLOAD,
            sinceMillis = runStartedAt
        )
        val nightscoutDeleteSucceeded = queueRepository.countByOperationAndStatusSinceForKeys(
            operation = RegistroLibreviewSyncOperation.DELETE,
            status = RegistroLibreviewSyncStatus.SYNCED_UPLOAD,
            sinceMillis = runStartedAt,
            keys = nightscoutManagedDeleteKeys
        )
        val existingSeriallessDeleteSucceeded = parseRepairReport(run.reportJson)[
            "seriallessDeleteSucceeded"
        ]?.toIntOrNull() ?: 0
        val seriallessRoundSucceeded = queueRepository.countByOperationAndStatusSinceForKeys(
            operation = RegistroLibreviewSyncOperation.DELETE,
            status = RegistroLibreviewSyncStatus.SYNCED_UPLOAD,
            sinceMillis = now,
            keys = seriallessManagedDeleteKeys
        )
        val seriallessDeleteSucceeded = (existingSeriallessDeleteSucceeded + seriallessRoundSucceeded)
            .coerceAtLeast(0)
        if (runResult.abortedByConsecutiveErrors || hasRemainingRepairQueue) {
            repairRunRepository.updateRun(runId) { current ->
                current.copy(
                    reportJson = buildRepairReport(
                        existing = current.reportJson,
                        mode = repairModeLabel,
                        wipeRoundActual = wipeRound,
                        wipeRoundsTarget = wipeRoundsTarget,
                        nightscoutManagedDeleteSucceeded = nightscoutDeleteSucceeded,
                        seriallessDeleteSucceeded = seriallessDeleteSucceeded
                    ),
                    updatedAt = now
                )
            }
            enqueueRepairPhaseSeconds(
                workManager = WorkManager.getInstance(applicationContext),
                mode = LibreviewExecutionMode.REPAIR_WIPE_ONLY,
                runId = runId,
                delaySeconds = 10,
                wipeRound = wipeRound
            )
            return WipePhaseResult(
                session = refreshedSession,
                keepRepairInProgress = true
            )
        }

        repairRunRepository.updateRun(runId) { current ->
            current.copy(
                deleteSucceeded = deleteSucceeded.coerceAtMost(current.deletePlanned),
                deleteFailedTolerated = deleteFailedTolerated,
                reportJson = buildRepairReport(
                    existing = current.reportJson,
                    mode = repairModeLabel,
                    wipeRoundActual = wipeRound,
                    wipeRoundsTarget = wipeRoundsTarget,
                    nightscoutManagedDeleteSucceeded = nightscoutDeleteSucceeded,
                    seriallessDeleteSucceeded = seriallessDeleteSucceeded
                ),
                updatedAt = now
            )
        }
        val snapshot = service.decodeRepairSnapshot(run.snapshotJson)
            ?: run {
                repairRunRepository.markFailed(
                    runId = runId,
                    reason = "Snapshot de reparación inválido",
                    reportJson = run.reportJson,
                    now = now
                )
                return WipePhaseResult(
                    session = refreshedSession,
                    keepRepairInProgress = false
                )
            }

        val shouldContinueWipe = if (repairModeLabel == "remote_read") {
            val wipePlan = service.buildRemoteAggressiveWipePlan(
                snapshot = snapshot,
                session = refreshedSession,
                fromMillis = repairRangeStart,
                toMillis = repairRangeEnd
            )
            val seriallessDetectedRemote = wipePlan.seriallessOverlap.size
            val overlapDeletesPlanned = wipePlan.unknownOverlap.size + seriallessDetectedRemote
            val knownManagedCount = (wipePlan.knownAppManaged.size - overlapDeletesPlanned)
                .coerceAtLeast(0)
            val snapshotWithSerialless = mergeSnapshotSeriallessKeys(
                snapshot = snapshot,
                wipePlan = wipePlan
            )
            runSnapshot = snapshotWithSerialless
            seriallessManagedDeleteKeys = snapshotWithSerialless.seriallessKnownRecordNumbers
                .map { it.channel to it.recordNumber }
                .toSet()
            repairRunRepository.updateRun(runId) { current ->
                current.copy(
                    knownManagedCount = knownManagedCount,
                    unknownOverlapCount = wipePlan.unknownOverlap.size + seriallessDetectedRemote,
                    foreignCount = wipePlan.foreign.size,
                    snapshotJson = service.encodeRepairSnapshot(snapshotWithSerialless),
                    reportJson = buildRepairReport(
                        existing = current.reportJson,
                        mode = repairModeLabel,
                        wipeRoundActual = wipeRound,
                        wipeRoundsTarget = wipeRoundsTarget,
                        overlapDeletesPlanned = overlapDeletesPlanned,
                        nightscoutManagedDeleteSucceeded = nightscoutDeleteSucceeded,
                        seriallessDetectedRemote = seriallessDetectedRemote,
                        seriallessDeleteSucceeded = seriallessDeleteSucceeded
                    ),
                    updatedAt = now
                )
            }
            if (wipePlan.knownAppManaged.isNotEmpty()) {
                if (wipeRound >= REMOTE_WIPE_MAX_ROUNDS) {
                    repairRunRepository.markFailed(
                        runId = runId,
                        reason = "Wipe remoto no convergió tras $REMOTE_WIPE_MAX_ROUNDS rondas",
                        reportJson = buildRepairReport(
                            existing = run.reportJson,
                            mode = repairModeLabel,
                            wipeRoundActual = wipeRound,
                            wipeRoundsTarget = wipeRoundsTarget,
                            residualKnownManaged = knownManagedCount,
                            unknownOverlap = wipePlan.unknownOverlap.size,
                            overlapDeletesPlanned = overlapDeletesPlanned,
                            nightscoutManagedDeleteSucceeded = nightscoutDeleteSucceeded,
                            seriallessDetectedRemote = seriallessDetectedRemote,
                            seriallessDeleteSucceeded = seriallessDeleteSucceeded
                        ),
                        now = now
                    )
                    return WipePhaseResult(
                        session = refreshedSession,
                        keepRepairInProgress = false
                    )
                }
                service.enqueueRepairWipeOnly(
                    wipePlan = wipePlan,
                    minRepeatsPerKey = WIPE_MIN_REPEATS_FOLLOW_UP_ROUNDS,
                    maxRepeatsPerKey = WIPE_MAX_REPEATS_PER_KEY,
                    now = now
                )
                val plannedOps = service.countWipeDeleteOps(
                    wipePlan = wipePlan,
                    minRepeatsPerKey = WIPE_MIN_REPEATS_FOLLOW_UP_ROUNDS,
                    maxRepeatsPerKey = WIPE_MAX_REPEATS_PER_KEY
                )
                val nightscoutRoundPlanned = service.countWipeDeleteOpsForKnownKeys(
                    wipePlan = wipePlan,
                    keys = nightscoutManagedDeleteKeys,
                    minRepeatsPerKey = WIPE_MIN_REPEATS_FOLLOW_UP_ROUNDS,
                    maxRepeatsPerKey = WIPE_MAX_REPEATS_PER_KEY
                )
                val seriallessRoundPlanned = service.countWipeDeleteOpsForKnownKeys(
                    wipePlan = wipePlan,
                    keys = seriallessManagedDeleteKeys,
                    minRepeatsPerKey = WIPE_MIN_REPEATS_FOLLOW_UP_ROUNDS,
                    maxRepeatsPerKey = WIPE_MAX_REPEATS_PER_KEY
                )
                val nextRound = wipeRound + 1
                repairRunRepository.updateRun(runId) { current ->
                    val existingNightscoutPlanned = parseRepairReport(current.reportJson)[
                        "nightscoutManagedDeletePlanned"
                    ]?.toIntOrNull() ?: 0
                    val existingSeriallessPlanned = parseRepairReport(current.reportJson)[
                        "seriallessDeletePlanned"
                    ]?.toIntOrNull() ?: 0
                    current.copy(
                        deletePlanned = current.deletePlanned + plannedOps,
                        reportJson = buildRepairReport(
                            existing = current.reportJson,
                            mode = repairModeLabel,
                            wipeRoundActual = nextRound,
                            wipeRoundsTarget = wipeRoundsTarget,
                            overlapDeletesPlanned = overlapDeletesPlanned,
                            nightscoutManagedDeletePlanned = existingNightscoutPlanned + nightscoutRoundPlanned,
                            nightscoutManagedDeleteSucceeded = nightscoutDeleteSucceeded,
                            seriallessDetectedRemote = seriallessDetectedRemote,
                            seriallessDeletePlanned = existingSeriallessPlanned + seriallessRoundPlanned,
                            seriallessDeleteSucceeded = seriallessDeleteSucceeded
                        ),
                        updatedAt = now
                    )
                }
                enqueueRepairPhaseSeconds(
                    workManager = WorkManager.getInstance(applicationContext),
                    mode = LibreviewExecutionMode.REPAIR_WIPE_ONLY,
                    runId = runId,
                    delaySeconds = 1,
                    wipeRound = nextRound
                )
                true
            } else {
                false
            }
        } else {
            if (wipeRound < BLIND_WIPE_ROUNDS) {
                val wipePlan = service.buildBlindWipePlan(snapshot = snapshot)
                service.enqueueRepairWipeOnly(
                    wipePlan = wipePlan,
                    minRepeatsPerKey = WIPE_MIN_REPEATS_FOLLOW_UP_ROUNDS,
                    maxRepeatsPerKey = WIPE_MAX_REPEATS_PER_KEY,
                    now = now
                )
                val plannedOps = service.countWipeDeleteOps(
                    wipePlan = wipePlan,
                    minRepeatsPerKey = WIPE_MIN_REPEATS_FOLLOW_UP_ROUNDS,
                    maxRepeatsPerKey = WIPE_MAX_REPEATS_PER_KEY
                )
                val nightscoutRoundPlanned = service.countWipeDeleteOpsForKnownKeys(
                    wipePlan = wipePlan,
                    keys = nightscoutManagedDeleteKeys,
                    minRepeatsPerKey = WIPE_MIN_REPEATS_FOLLOW_UP_ROUNDS,
                    maxRepeatsPerKey = WIPE_MAX_REPEATS_PER_KEY
                )
                val nextRound = wipeRound + 1
                repairRunRepository.updateRun(runId) { current ->
                    val existingNightscoutPlanned = parseRepairReport(current.reportJson)[
                        "nightscoutManagedDeletePlanned"
                    ]?.toIntOrNull() ?: 0
                    current.copy(
                        knownManagedCount = wipePlan.knownAppManaged.size,
                        unknownOverlapCount = wipePlan.unknownOverlap.size,
                        foreignCount = wipePlan.foreign.size,
                        deletePlanned = current.deletePlanned + plannedOps,
                        reportJson = buildRepairReport(
                            existing = current.reportJson,
                            mode = repairModeLabel,
                            wipeRoundActual = nextRound,
                            wipeRoundsTarget = BLIND_WIPE_ROUNDS,
                            nightscoutManagedDeletePlanned = existingNightscoutPlanned + nightscoutRoundPlanned,
                            nightscoutManagedDeleteSucceeded = nightscoutDeleteSucceeded
                        ),
                        updatedAt = now
                    )
                }
                enqueueRepairPhaseSeconds(
                    workManager = WorkManager.getInstance(applicationContext),
                    mode = LibreviewExecutionMode.REPAIR_WIPE_ONLY,
                    runId = runId,
                    delaySeconds = 1,
                    wipeRound = nextRound
                )
                true
            } else {
                repairRunRepository.markBlocked(
                    runId = runId,
                    reason = "blind_wipe_requires_manual_upsert",
                    reportJson = buildRepairReport(
                        existing = run.reportJson,
                        mode = "blind_wipe",
                        wipeRoundActual = wipeRound,
                        wipeRoundsTarget = BLIND_WIPE_ROUNDS,
                        wipeCompleted = true,
                        upsertBlocked = true,
                        manualUpsertRequired = true,
                        nightscoutManagedDeleteSucceeded = nightscoutDeleteSucceeded,
                        seriallessDeleteSucceeded = seriallessDeleteSucceeded
                    ),
                    now = now
                )
                return WipePhaseResult(
                    session = refreshedSession,
                    keepRepairInProgress = false
                )
            }
        }

        if (!shouldContinueWipe) {
            val latestReport = repairRunRepository.getRun(runId)?.reportJson ?: run.reportJson
            repairRunRepository.markBlocked(
                runId = runId,
                reason = if (repairModeLabel == "remote_read") {
                    "remote_wipe_requires_manual_partial_upsert"
                } else {
                    "blind_wipe_requires_manual_upsert"
                },
                reportJson = buildRepairReport(
                    existing = latestReport,
                    mode = repairModeLabel,
                    wipeRoundActual = wipeRound,
                    wipeRoundsTarget = wipeRoundsTarget,
                    wipeCompleted = true,
                    upsertBlocked = true,
                    manualUpsertRequired = true,
                    nightscoutManagedDeleteSucceeded = nightscoutDeleteSucceeded,
                    seriallessDeleteSucceeded = seriallessDeleteSucceeded
                ),
                now = now
            )
            return WipePhaseResult(
                session = refreshedSession,
                keepRepairInProgress = false
            )
        }
        return WipePhaseResult(
            session = refreshedSession,
            keepRepairInProgress = true
        )
    }

    private suspend fun runManualPartialUpsertPhase(
        runIdInput: Long?,
        queueRepository: RegistroLibreviewSyncRepository,
        repairRunRepository: LibreviewRepairRunRepository,
        service: LibreviewRegistrosSyncService,
        nightscoutMergeService: NightscoutRegistrosSyncService,
        libreviewRepository: LibreviewRepository,
        libreviewSecretStore: LibreviewSecretStore,
        profile: com.diabetes.calculator.data.entity.UsuarioProfile,
        profileLinkMinutes: Int,
        profileLinkUnits: Float,
        localRepairScopeStart: Long,
        repairRangeStart: Long,
        repairRangeEnd: Long,
        email: String,
        password: String,
        session: com.diabetes.calculator.data.model.LibreviewSession,
        now: Long
    ): ManualPartialPhaseResult {
        val runId = runIdInput ?: repairRunRepository.getLatestActiveRun()?.id
        if (runId == null) {
            return ManualPartialPhaseResult(
                session = session,
                keepRepairInProgress = false
            )
        }
        queueRepository.deleteByOperation(RegistroLibreviewSyncOperation.DELETE)
        val run = repairRunRepository.getRun(runId)
            ?: return ManualPartialPhaseResult(
                session = session,
                keepRepairInProgress = false
            )
        val runStartedAt = run.startedAt
        val parsedReport = parseRepairReport(run.reportJson)
        val reportMode = parsedReport["mode"].orEmpty()
        val wipeRoundActual = parsedReport["wipeRoundActual"]
            ?.toIntOrNull()
            ?.coerceAtLeast(1)
        repairRunRepository.markPhase(
            runId = runId,
            phase = LibreviewRepairPhase.UPSERT_ONLY,
            status = LibreviewRepairRunStatus.IN_PROGRESS,
            now = now
        )
        val hasQueueBefore = queueRepository.getPendingOrFailed().isNotEmpty()
        if (!hasQueueBefore) {
            nightscoutMergeService.reconcileLocalDuplicatesOnly(
                now = now,
                linkOffsetMinutes = profileLinkMinutes,
                linkOffsetUnits = profileLinkUnits
            )
            val rebuiltSnapshot = service.buildLocalRepairSnapshot(
                repairLinkOffsetMinutes = profileLinkMinutes,
                repairLinkOffsetUnits = profileLinkUnits,
                minEventTimestampMillis = localRepairScopeStart,
                now = now
            )
            val partialResult = service.enqueueRepairUpsertPartialManual(
                snapshot = rebuiltSnapshot,
                session = session,
                fromMillis = repairRangeStart,
                toMillis = repairRangeEnd,
                now = now
            )
            val partialReport = buildRepairReport(
                existing = run.reportJson,
                mode = if (reportMode.isNotBlank()) reportMode else "blind_wipe",
                wipeRoundActual = wipeRoundActual,
                wipeRoundsTarget = if (reportMode == "remote_read") {
                    REMOTE_WIPE_MAX_ROUNDS
                } else {
                    BLIND_WIPE_ROUNDS
                },
                upsertBlocked = false,
                manualUpsertRequired = false,
                upsertPartialPlanned = partialResult.planned,
                upsertPartialSkippedByRemote = partialResult.skippedByRemoteMatch,
                upsertPartialLinked = partialResult.linkedToRemote
            )
            if (partialResult.failedRead) {
                repairRunRepository.markBlocked(
                    runId = runId,
                    reason = "manual_upsert_requires_remote_read",
                    reportJson = buildRepairReport(
                        existing = partialReport,
                        manualUpsertRequired = true,
                        upsertBlocked = true
                    ),
                    now = now
                )
                return ManualPartialPhaseResult(
                    session = session,
                    keepRepairInProgress = false
                )
            }
            repairRunRepository.updateRun(runId) { current ->
                current.copy(
                    snapshotJson = service.encodeRepairSnapshot(rebuiltSnapshot),
                    canonicalRecords = rebuiltSnapshot.canonicalRecords,
                    upsertPlanned = partialResult.planned,
                    reportJson = partialReport,
                    updatedAt = now
                )
            }
            if (partialResult.planned == 0) {
                enqueueRepairPhaseSeconds(
                    workManager = WorkManager.getInstance(applicationContext),
                    mode = LibreviewExecutionMode.REPAIR_VERIFY,
                    runId = runId,
                    delaySeconds = 1
                )
                return ManualPartialPhaseResult(
                    session = session,
                    keepRepairInProgress = true
                )
            }
        } else if (service.decodeRepairSnapshot(run.snapshotJson) == null) {
            repairRunRepository.markFailed(
                runId = runId,
                reason = "Snapshot de reparación inválido",
                reportJson = run.reportJson,
                now = now
            )
            return ManualPartialPhaseResult(
                session = session,
                keepRepairInProgress = false
            )
        }

        val upsertSync = runSyncWithAutoDeviceRecovery(
            service = service,
            libreviewRepository = libreviewRepository,
            libreviewSecretStore = libreviewSecretStore,
            regionOverride = profile.libreviewRegionOverride,
            email = email,
            password = password,
            profileForSync = profile.copy(libreviewSyncActivo = true),
            session = session,
            bypassFailureBackoff = true,
            prioritizeDeleteOperations = false,
            repairMode = true,
            allowPendingInsulin = false,
            now = now,
            contextLabel = "repair_upsert_partial_manual"
        )
        val refreshedSession = upsertSync.session
        if (upsertSync.unrecoverableWrongDevice) {
            enqueueRepairPhaseSeconds(
                workManager = WorkManager.getInstance(applicationContext),
                mode = LibreviewExecutionMode.REPAIR_UPSERT_PARTIAL_MANUAL,
                runId = runId,
                delaySeconds = 10
            )
            return ManualPartialPhaseResult(
                session = refreshedSession,
                keepRepairInProgress = true
            )
        }

        val runResult = upsertSync.runResult
        val remaining = queueRepository.getPendingOrFailed().isNotEmpty()
        val upsertSucceeded = queueRepository.countByOperationAndStatusSince(
            operation = RegistroLibreviewSyncOperation.UPSERT,
            status = RegistroLibreviewSyncStatus.SYNCED_UPLOAD,
            sinceMillis = runStartedAt
        )
        val upsertFailed = queueRepository.countByOperationAndStatusSince(
            operation = RegistroLibreviewSyncOperation.UPSERT,
            status = RegistroLibreviewSyncStatus.FAILED,
            sinceMillis = runStartedAt
        )
        repairRunRepository.updateRun(runId) { current ->
            current.copy(
                upsertSucceeded = upsertSucceeded.coerceAtMost(current.upsertPlanned),
                upsertFailed = upsertFailed,
                updatedAt = now
            )
        }
        if (runResult.abortedByConsecutiveErrors || remaining) {
            enqueueRepairPhaseSeconds(
                workManager = WorkManager.getInstance(applicationContext),
                mode = LibreviewExecutionMode.REPAIR_UPSERT_PARTIAL_MANUAL,
                runId = runId,
                delaySeconds = 10
            )
        } else {
            enqueueRepairPhaseSeconds(
                workManager = WorkManager.getInstance(applicationContext),
                mode = LibreviewExecutionMode.REPAIR_VERIFY,
                runId = runId,
                delaySeconds = 1
            )
        }
        return ManualPartialPhaseResult(
            session = refreshedSession,
            keepRepairInProgress = true
        )
    }

    private suspend fun runSyncWithAutoDeviceRecovery(
        service: LibreviewRegistrosSyncService,
        libreviewRepository: LibreviewRepository,
        libreviewSecretStore: LibreviewSecretStore,
        regionOverride: String?,
        email: String,
        password: String,
        profileForSync: com.diabetes.calculator.data.entity.UsuarioProfile,
        session: com.diabetes.calculator.data.model.LibreviewSession,
        bypassFailureBackoff: Boolean,
        prioritizeDeleteOperations: Boolean,
        repairMode: Boolean,
        allowPendingInsulin: Boolean,
        now: Long,
        contextLabel: String
    ): SyncWithAutoRecoveryResult {
        val firstRun = service.sync(
            profile = profileForSync,
            session = session,
            bypassFailureBackoff = bypassFailureBackoff,
            prioritizeDeleteOperations = prioritizeDeleteOperations,
            repairMode = repairMode,
            allowPendingInsulin = allowPendingInsulin,
            now = now
        )
        if (!isRecoverableSyncError(libreviewRepository.lastErrorMessage)) {
            return SyncWithAutoRecoveryResult(
                runResult = firstRun,
                session = session,
                unrecoverableWrongDevice = false
            )
        }

        val recoverableError = libreviewRepository.lastErrorMessage.orEmpty()
        val wrongDevice = isWrongDeviceError(recoverableError)
        val dnsResolution = isDnsResolutionError(recoverableError)
        if (wrongDevice) {
            Log.w(
                TAG,
                "LV wrongDevice detectado en $contextLabel. Reset automático y continuación desde fase actual."
            )
            libreviewSecretStore.resetDeviceIdentity()
        } else if (dnsResolution) {
            Log.w(
                TAG,
                "LV DNS host unresolved en $contextLabel. Limpiando sesión y reautenticando sin abortar fase."
            )
            libreviewSecretStore.clearSession()
        }
        val refreshedSession = authenticateLibreviewSession(
            libreviewRepository = libreviewRepository,
            libreviewSecretStore = libreviewSecretStore,
            regionOverride = regionOverride,
            email = email,
            password = password
        ) ?: return SyncWithAutoRecoveryResult(
            runResult = firstRun,
            session = session,
            unrecoverableWrongDevice = true
        )

        val secondRun = service.sync(
            profile = profileForSync,
            session = refreshedSession,
            bypassFailureBackoff = true,
            prioritizeDeleteOperations = prioritizeDeleteOperations,
            repairMode = repairMode,
            allowPendingInsulin = allowPendingInsulin,
            now = now
        )
        val mergedRun = mergeSyncRunResults(first = firstRun, second = secondRun)
        val stillRecoverableError = isRecoverableSyncError(libreviewRepository.lastErrorMessage)
        if (!stillRecoverableError) {
            Log.i(TAG, "LV recovery aplicado en $contextLabel; el proceso continúa desde la cola actual.")
        }
        return SyncWithAutoRecoveryResult(
            runResult = mergedRun,
            session = refreshedSession,
            unrecoverableWrongDevice = stillRecoverableError
        )
    }

    private fun mergeSyncRunResults(
        first: LibreviewSyncRunResult,
        second: LibreviewSyncRunResult
    ): LibreviewSyncRunResult {
        return LibreviewSyncRunResult(
            processedPending = first.processedPending + second.processedPending,
            failedPending = second.failedPending,
            maxFailedAttempts = max(first.maxFailedAttempts, second.maxFailedAttempts),
            abortedByConsecutiveErrors = second.abortedByConsecutiveErrors
        )
    }

    private suspend fun authenticateLibreviewSession(
        libreviewRepository: LibreviewRepository,
        libreviewSecretStore: LibreviewSecretStore,
        regionOverride: String?,
        email: String,
        password: String
    ): com.diabetes.calculator.data.model.LibreviewSession? {
        val pinnedRegionOverride = regionOverride
            ?.takeIf { it.isNotBlank() }
        var effectiveRegionOverride = pinnedRegionOverride ?: libreviewSecretStore.getCountryCode()
        var deviceId = libreviewSecretStore.getOrCreateDeviceId()
        var session = libreviewRepository.authenticateAuto(
            overrideCountry = effectiveRegionOverride,
            email = email,
            password = password,
            deviceId = deviceId
        )
        if (session == null && isWrongDeviceError(libreviewRepository.lastErrorMessage)) {
            deviceId = libreviewSecretStore.regenerateDeviceId()
            session = libreviewRepository.authenticateAuto(
                overrideCountry = effectiveRegionOverride,
                email = email,
                password = password,
                deviceId = deviceId
            )
        }
        if (session == null && isWrongDeviceError(libreviewRepository.lastErrorMessage)) {
            Log.w(TAG, "LV auth sigue en wrongDevice; reset completo de identidad y reintento final.")
            libreviewSecretStore.resetDeviceIdentity()
            effectiveRegionOverride = pinnedRegionOverride ?: libreviewSecretStore.getCountryCode()
            deviceId = libreviewSecretStore.getOrCreateDeviceId()
            session = libreviewRepository.authenticateAuto(
                overrideCountry = effectiveRegionOverride,
                email = email,
                password = password,
                deviceId = deviceId
            )
        }
        session?.let {
            libreviewSecretStore.setBaseUrl(it.baseUrl)
            libreviewSecretStore.setApiKey(it.apiKey)
            libreviewSecretStore.setUserToken(it.userToken)
            libreviewSecretStore.setAccountId(it.accountId)
            libreviewSecretStore.setCountryCode(it.countryCode)
            libreviewSecretStore.setLastAuthAt(it.authenticatedAt)
        }
        return session
    }

    private suspend fun markPendingAsFailed(
        queueRepository: RegistroLibreviewSyncRepository,
        error: String,
        now: Long
    ) {
        val pending = queueRepository.getPendingOrFailed()
        pending.forEach { item ->
            if (RegistroLibreviewSyncStatus.fromValue(item.status) != RegistroLibreviewSyncStatus.PENDING) {
                return@forEach
            }
            val channel = RegistroLibreviewSyncChannel.fromValue(item.channel) ?: return@forEach
            queueRepository.markFailed(
                registroId = item.registroId,
                channel = channel,
                error = error,
                now = now
            )
        }
    }

    private fun parseRepairReport(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val values = linkedMapOf<String, String>()
        raw.split(";").forEach { chunk ->
            val token = chunk.trim()
            if (token.isEmpty()) return@forEach
            val separator = token.indexOf('=')
            if (separator <= 0 || separator == token.lastIndex) return@forEach
            val key = token.substring(0, separator).trim()
            val value = token.substring(separator + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                values[key] = value
            }
        }
        return values
    }

    private fun mergeSnapshotSeriallessKeys(
        snapshot: LibreviewRepairSnapshot,
        wipePlan: LibreviewWipePlan
    ): LibreviewRepairSnapshot {
        if (wipePlan.seriallessOverlap.isEmpty()) return snapshot
        val existing = snapshot.seriallessKnownRecordNumbers
            .map { Triple(it.channel, it.recordNumber, it.eventTimestampMillis) }
            .toMutableSet()
        val merged = snapshot.seriallessKnownRecordNumbers.toMutableList()
        wipePlan.seriallessOverlap.forEach { entry ->
            val key = Triple(
                entry.channel,
                entry.recordNumber,
                entry.eventTimestampMillis
            )
            if (!existing.add(key)) return@forEach
            merged += LibreviewKnownRecordNumber(
                channel = entry.channel,
                recordNumber = entry.recordNumber,
                eventTimestampMillis = entry.eventTimestampMillis
            )
        }
        return snapshot.copy(seriallessKnownRecordNumbers = merged)
    }

    private fun isWrongDeviceError(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val normalized = raw.lowercase()
        return normalized.contains("wrongdeviceintoken") ||
            normalized.contains("wrongdeviceforuser") ||
            normalized.contains("status=20")
    }

    private fun isDnsResolutionError(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val normalized = raw.lowercase()
        return normalized.contains("unable to resolve host") ||
            normalized.contains("no address associated with hostname")
    }

    private fun isRecoverableSyncError(raw: String?): Boolean {
        return isWrongDeviceError(raw) || isDnsResolutionError(raw)
    }

    private fun buildRepairReport(
        existing: String? = null,
        mode: String? = null,
        wipeRoundActual: Int? = null,
        wipeRoundsTarget: Int? = null,
        probeEndpoint: String? = null,
        probeFailed: String? = null,
        unknownOverlap: Int? = null,
        residualKnownManaged: Int? = null,
        overlapDeletesPlanned: Int? = null,
        wipeCompleted: Boolean? = null,
        upsertBlocked: Boolean? = null,
        manualUpsertRequired: Boolean? = null,
        upsertPartialPlanned: Int? = null,
        upsertPartialSkippedByRemote: Int? = null,
        upsertPartialLinked: Int? = null,
        nightscoutImportSkippedUpserts: Int? = null,
        nightscoutManagedDeletePlanned: Int? = null,
        nightscoutManagedDeleteSucceeded: Int? = null,
        seriallessDetectedRemote: Int? = null,
        seriallessDeletePlanned: Int? = null,
        seriallessDeleteSucceeded: Int? = null,
        repairScopeFromDate: String? = null,
        cutoffContextDate: String? = null
    ): String {
        val values = linkedMapOf<String, String>()
        values.putAll(parseRepairReport(existing))
        mode?.takeIf { it.isNotBlank() }?.let { values["mode"] = it }
        wipeRoundActual?.takeIf { it > 0 }?.let { values["wipeRoundActual"] = it.toString() }
        wipeRoundsTarget?.takeIf { it > 0 }?.let { values["wipeRoundsTarget"] = it.toString() }
        probeEndpoint?.takeIf { it.isNotBlank() }?.let { values["probeEndpoint"] = it }
        probeFailed?.takeIf { it.isNotBlank() }?.let { values["probeFailed"] = it }
        unknownOverlap?.let { values["unknownOverlap"] = it.toString() }
        residualKnownManaged?.let { values["residualKnownManaged"] = it.toString() }
        overlapDeletesPlanned?.let { values["overlapDeletesPlanned"] = it.toString() }
        wipeCompleted?.let { values["wipeCompleted"] = it.toString() }
        upsertBlocked?.let { values["upsertBlocked"] = it.toString() }
        manualUpsertRequired?.let { values["manualUpsertRequired"] = it.toString() }
        upsertPartialPlanned?.let { values["upsertPartialPlanned"] = it.toString() }
        upsertPartialSkippedByRemote?.let { values["upsertPartialSkippedByRemote"] = it.toString() }
        upsertPartialLinked?.let { values["upsertPartialLinked"] = it.toString() }
        nightscoutImportSkippedUpserts?.let { values["nightscoutImportSkippedUpserts"] = it.toString() }
        nightscoutManagedDeletePlanned?.let { values["nightscoutManagedDeletePlanned"] = it.toString() }
        nightscoutManagedDeleteSucceeded?.let { values["nightscoutManagedDeleteSucceeded"] = it.toString() }
        seriallessDetectedRemote?.let { values["seriallessDetectedRemote"] = it.toString() }
        seriallessDeletePlanned?.let { values["seriallessDeletePlanned"] = it.toString() }
        seriallessDeleteSucceeded?.let { values["seriallessDeleteSucceeded"] = it.toString() }
        repairScopeFromDate?.takeIf { it.isNotBlank() }?.let { values["repairScopeFromDate"] = it }
        cutoffContextDate?.takeIf { it.isNotBlank() }?.let { values["cutoffContextDate"] = it }
        return values.entries.joinToString(separator = ";") { (key, value) -> "$key=$value" }
    }

    companion object {
        private const val TAG = "LibreviewSyncWorker"
        private const val WORK_NAME_PERIODIC = "libreview_records_sync_periodic"
        private const val WORK_NAME_NOW = "libreview_records_sync_now"
        private const val WORK_NAME_REPAIR = "libreview_records_sync_repair"
        private const val WORK_NAME_RETRY = "libreview_records_sync_retry"
        private const val KEY_FORCE_MANUAL = "force_manual"
        private const val KEY_EMAIL_OVERRIDE = "email_override"
        private const val KEY_PASSWORD_OVERRIDE = "password_override"
        private const val KEY_EXECUTION_MODE = "execution_mode"
        private const val KEY_REPAIR_RUN_ID = "repair_run_id"
        private const val KEY_WIPE_ROUND = "wipe_round"
        private const val MODE_NORMAL = "NORMAL"
        private const val MODE_REPAIR_RESET = "REPAIR_RESET"
        private const val MODE_REPAIR_RETRY_CHAIN = "REPAIR_RETRY_CHAIN"
        private const val MODE_REPAIR_DISCOVERY = "REPAIR_DISCOVERY"
        private const val MODE_REPAIR_WIPE_ONLY = "REPAIR_WIPE_ONLY"
        private const val MODE_REPAIR_UPSERT_ONLY = "REPAIR_UPSERT_ONLY"
        private const val MODE_REPAIR_UPSERT_PARTIAL_MANUAL = "REPAIR_UPSERT_PARTIAL_MANUAL"
        private const val MODE_REPAIR_VERIFY = "REPAIR_VERIFY"
        private const val REPAIR_SCOPE_FROM_YEAR = 2026
        private const val REPAIR_SCOPE_FROM_MONTH = Calendar.FEBRUARY
        private const val REPAIR_SCOPE_FROM_DAY = 9
        private const val REPAIR_SCOPE_FROM_CONTEXT_DATE = "2026-02-09"
        private const val CUTOFF_CONTEXT_DATE = "2026-02-22"
        private const val MANUAL_INSULIN_SCOPE_FROM_YEAR = 2026
        private const val MANUAL_INSULIN_SCOPE_FROM_MONTH = Calendar.FEBRUARY
        private const val MANUAL_INSULIN_SCOPE_FROM_DAY = 22
        private const val MANUAL_INSULIN_SCOPE_FROM_HOUR = 18
        private const val BLIND_WIPE_ROUNDS = 4
        private const val REMOTE_WIPE_MAX_ROUNDS = 6
        private const val WIPE_MIN_REPEATS_ROUND_ONE = 6
        private const val WIPE_MIN_REPEATS_FOLLOW_UP_ROUNDS = 2
        private const val WIPE_MAX_REPEATS_PER_KEY = 24
        private const val SYNC_COOLDOWN_MILLIS = 2L * 60_000L
        private val syncMutex = Mutex()
        @Volatile
        private var repairInProgress: Boolean = false

        private fun computeRepairScopeFromMillis(now: Long): Long {
            val scopeStart = Calendar.getInstance().apply {
                set(Calendar.YEAR, REPAIR_SCOPE_FROM_YEAR)
                set(Calendar.MONTH, REPAIR_SCOPE_FROM_MONTH)
                set(Calendar.DAY_OF_MONTH, REPAIR_SCOPE_FROM_DAY)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            return scopeStart.coerceAtMost(now)
        }

        private fun computeManualInsulinScopeFromMillis(now: Long): Long {
            val scopeStart = Calendar.getInstance().apply {
                set(Calendar.YEAR, MANUAL_INSULIN_SCOPE_FROM_YEAR)
                set(Calendar.MONTH, MANUAL_INSULIN_SCOPE_FROM_MONTH)
                set(Calendar.DAY_OF_MONTH, MANUAL_INSULIN_SCOPE_FROM_DAY)
                set(Calendar.HOUR_OF_DAY, MANUAL_INSULIN_SCOPE_FROM_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            return scopeStart.coerceAtMost(now)
        }

        fun enqueuePeriodic(workManager: WorkManager) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<LibreviewSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueNow(
            workManager: WorkManager,
            forceManual: Boolean = false,
            emailOverride: String? = null,
            passwordOverride: String? = null
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val trimmedEmail = emailOverride?.trim()?.ifEmpty { null }
            val trimmedPassword = passwordOverride?.trim()?.ifEmpty { null }
            val request = OneTimeWorkRequestBuilder<LibreviewSyncWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_FORCE_MANUAL to forceManual,
                        KEY_EXECUTION_MODE to MODE_NORMAL,
                        KEY_EMAIL_OVERRIDE to trimmedEmail,
                        KEY_PASSWORD_OVERRIDE to trimmedPassword
                    )
                )
                .build()
            workManager.enqueueUniqueWork(
                WORK_NAME_NOW,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun enqueueRepairReset(
            workManager: WorkManager,
            emailOverride: String? = null,
            passwordOverride: String? = null
        ) {
            // Stop normal one-shot/retry runs before starting repair.
            workManager.cancelUniqueWork(WORK_NAME_NOW)
            workManager.cancelUniqueWork(WORK_NAME_RETRY)
            repairInProgress = true
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val trimmedEmail = emailOverride?.trim()?.ifEmpty { null }
            val trimmedPassword = passwordOverride?.trim()?.ifEmpty { null }
            val request = OneTimeWorkRequestBuilder<LibreviewSyncWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_FORCE_MANUAL to true,
                        KEY_EXECUTION_MODE to MODE_REPAIR_DISCOVERY,
                        KEY_EMAIL_OVERRIDE to trimmedEmail,
                        KEY_PASSWORD_OVERRIDE to trimmedPassword
                    )
                )
                .build()
            workManager.enqueueUniqueWork(
                WORK_NAME_REPAIR,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun enqueueRepairManualPartialUpsert(
            workManager: WorkManager,
            runId: Long,
            emailOverride: String? = null,
            passwordOverride: String? = null
        ) {
            if (runId <= 0L) return
            // Stop normal one-shot/retry runs before continuing manual repair phase.
            workManager.cancelUniqueWork(WORK_NAME_NOW)
            workManager.cancelUniqueWork(WORK_NAME_RETRY)
            repairInProgress = true
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val trimmedEmail = emailOverride?.trim()?.ifEmpty { null }
            val trimmedPassword = passwordOverride?.trim()?.ifEmpty { null }
            val request = OneTimeWorkRequestBuilder<LibreviewSyncWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_FORCE_MANUAL to true,
                        KEY_EXECUTION_MODE to MODE_REPAIR_UPSERT_PARTIAL_MANUAL,
                        KEY_REPAIR_RUN_ID to runId,
                        KEY_EMAIL_OVERRIDE to trimmedEmail,
                        KEY_PASSWORD_OVERRIDE to trimmedPassword
                    )
                )
                .build()
            workManager.enqueueUniqueWork(
                WORK_NAME_REPAIR,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun enqueueRetry(workManager: WorkManager, delayMinutes: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<LibreviewSyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setInputData(
                    workDataOf(
                        KEY_EXECUTION_MODE to MODE_NORMAL
                    )
                )
                .build()
            workManager.enqueueUniqueWork(
                WORK_NAME_RETRY,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun abortCurrentOperation(workManager: WorkManager) {
            workManager.cancelUniqueWork(WORK_NAME_REPAIR)
            workManager.cancelUniqueWork(WORK_NAME_NOW)
            workManager.cancelUniqueWork(WORK_NAME_RETRY)
            repairInProgress = false
        }

        private fun enqueueRepairPhaseSeconds(
            workManager: WorkManager,
            mode: LibreviewExecutionMode,
            runId: Long?,
            delaySeconds: Long,
            wipeRound: Int = 1
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val modeValue = when (mode) {
                LibreviewExecutionMode.NORMAL -> MODE_NORMAL
                LibreviewExecutionMode.REPAIR_RESET -> MODE_REPAIR_RESET
                LibreviewExecutionMode.REPAIR_RETRY_CHAIN -> MODE_REPAIR_RETRY_CHAIN
                LibreviewExecutionMode.REPAIR_DISCOVERY -> MODE_REPAIR_DISCOVERY
                LibreviewExecutionMode.REPAIR_WIPE_ONLY -> MODE_REPAIR_WIPE_ONLY
                LibreviewExecutionMode.REPAIR_UPSERT_ONLY -> MODE_REPAIR_UPSERT_ONLY
                LibreviewExecutionMode.REPAIR_UPSERT_PARTIAL_MANUAL -> MODE_REPAIR_UPSERT_PARTIAL_MANUAL
                LibreviewExecutionMode.REPAIR_VERIFY -> MODE_REPAIR_VERIFY
            }
            val request = OneTimeWorkRequestBuilder<LibreviewSyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_FORCE_MANUAL to true,
                        KEY_EXECUTION_MODE to modeValue,
                        KEY_REPAIR_RUN_ID to (runId ?: -1L),
                        KEY_WIPE_ROUND to wipeRound.coerceAtLeast(1)
                    )
                )
                .build()
            workManager.enqueueUniqueWork(
                WORK_NAME_REPAIR,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    private enum class LibreviewExecutionMode(val value: String) {
        NORMAL("NORMAL"),
        REPAIR_RESET("REPAIR_RESET"),
        REPAIR_RETRY_CHAIN("REPAIR_RETRY_CHAIN"),
        REPAIR_DISCOVERY("REPAIR_DISCOVERY"),
        REPAIR_WIPE_ONLY("REPAIR_WIPE_ONLY"),
        REPAIR_UPSERT_ONLY("REPAIR_UPSERT_ONLY"),
        REPAIR_UPSERT_PARTIAL_MANUAL("REPAIR_UPSERT_PARTIAL_MANUAL"),
        REPAIR_VERIFY("REPAIR_VERIFY");

        companion object {
            fun fromValue(value: String?): LibreviewExecutionMode {
                return entries.firstOrNull { it.value == value } ?: NORMAL
            }
        }
    }
}
