package com.x8bit.bitwarden.data.auth.manager

import com.bitwarden.core.AuthRequestResponse
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.auth.datasource.network.service.AuthRequestsService
import com.x8bit.bitwarden.data.auth.datasource.network.service.NewAuthRequestService
import com.x8bit.bitwarden.data.auth.datasource.sdk.AuthSdkSource
import com.x8bit.bitwarden.data.auth.manager.model.AuthRequest
import com.x8bit.bitwarden.data.auth.manager.model.AuthRequestResult
import com.x8bit.bitwarden.data.auth.manager.model.AuthRequestUpdatesResult
import com.x8bit.bitwarden.data.auth.manager.model.AuthRequestsResult
import com.x8bit.bitwarden.data.auth.manager.model.AuthRequestsUpdatesResult
import com.x8bit.bitwarden.data.auth.manager.model.CreateAuthRequestResult
import com.x8bit.bitwarden.data.auth.repository.model.UserFingerprintResult
import com.x8bit.bitwarden.data.platform.util.flatMap
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.time.Clock
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val PASSWORDLESS_NOTIFICATION_TIMEOUT_MILLIS: Long = 15L * 60L * 1_000L
private const val PASSWORDLESS_NOTIFICATION_RETRY_INTERVAL_MILLIS: Long = 4L * 1_000L
private const val PASSWORDLESS_APPROVER_INTERVAL_MILLIS: Long = 5L * 60L * 1_000L

/**
 * Default implementation of [AuthRequestManager].
 */
@Singleton
class AuthRequestManagerImpl(
    private val clock: Clock,
    private val authRequestsService: AuthRequestsService,
    private val newAuthRequestService: NewAuthRequestService,
    private val authDiskSource: AuthDiskSource,
    private val authSdkSource: AuthSdkSource,
    private val vaultSdkSource: VaultSdkSource,
) : AuthRequestManager {
    private val activeUserId: String? get() = authDiskSource.userState?.activeUserId

    override fun getAuthRequestsWithUpdates(): Flow<AuthRequestsUpdatesResult> = flow {
        while (currentCoroutineContext().isActive) {
            when (val result = getAuthRequests()) {
                AuthRequestsResult.Error -> emit(AuthRequestsUpdatesResult.Error)

                is AuthRequestsResult.Success -> {
                    emit(AuthRequestsUpdatesResult.Update(authRequests = result.authRequests))
                }
            }
            delay(timeMillis = PASSWORDLESS_APPROVER_INTERVAL_MILLIS)
        }
    }

    @Suppress("LongMethod")
    override fun createAuthRequestWithUpdates(
        email: String,
    ): Flow<CreateAuthRequestResult> = flow {
        val initialResult = createNewAuthRequest(email).getOrNull() ?: run {
            emit(CreateAuthRequestResult.Error)
            return@flow
        }
        val authRequestResponse = initialResult.authRequestResponse
        var authRequest = initialResult.authRequest
        emit(CreateAuthRequestResult.Update(authRequest))

        var isComplete = false
        while (currentCoroutineContext().isActive && !isComplete) {
            delay(timeMillis = PASSWORDLESS_NOTIFICATION_RETRY_INTERVAL_MILLIS)
            newAuthRequestService
                .getAuthRequestUpdate(
                    requestId = authRequest.id,
                    accessCode = authRequestResponse.accessCode,
                )
                .map { request ->
                    AuthRequest(
                        id = request.id,
                        publicKey = request.publicKey,
                        platform = request.platform,
                        ipAddress = request.ipAddress,
                        key = request.key,
                        masterPasswordHash = request.masterPasswordHash,
                        creationDate = request.creationDate,
                        responseDate = request.responseDate,
                        requestApproved = request.requestApproved ?: false,
                        originUrl = request.originUrl,
                        fingerprint = authRequest.fingerprint,
                    )
                }
                .fold(
                    onFailure = { emit(CreateAuthRequestResult.Error) },
                    onSuccess = { updateAuthRequest ->
                        when {
                            updateAuthRequest.requestApproved -> {
                                isComplete = true
                                emit(
                                    CreateAuthRequestResult.Success(
                                        authRequest = updateAuthRequest,
                                        authRequestResponse = authRequestResponse,
                                    ),
                                )
                            }

                            !updateAuthRequest.requestApproved &&
                                updateAuthRequest.responseDate != null -> {
                                isComplete = true
                                emit(CreateAuthRequestResult.Declined)
                            }

                            updateAuthRequest
                                .creationDate
                                .toInstant()
                                .plusMillis(PASSWORDLESS_NOTIFICATION_TIMEOUT_MILLIS)
                                .isBefore(clock.instant()) -> {
                                isComplete = true
                                emit(CreateAuthRequestResult.Expired)
                            }

                            else -> {
                                authRequest = updateAuthRequest
                                emit(CreateAuthRequestResult.Update(authRequest))
                            }
                        }
                    },
                )
        }
    }

    private fun getAuthRequest(
        initialRequest: suspend () -> AuthRequestUpdatesResult,
    ): Flow<AuthRequestUpdatesResult> = flow {
        val result = initialRequest()
        emit(result)
        if (result is AuthRequestUpdatesResult.Error) return@flow
        var isComplete = false
        while (coroutineContext.isActive && !isComplete) {
            delay(PASSWORDLESS_APPROVER_INTERVAL_MILLIS)
            val updateResult = result as AuthRequestUpdatesResult.Update
            authRequestsService
                .getAuthRequest(result.authRequest.id)
                .map { request ->
                    AuthRequest(
                        id = request.id,
                        publicKey = request.publicKey,
                        platform = request.platform,
                        ipAddress = request.ipAddress,
                        key = request.key,
                        masterPasswordHash = request.masterPasswordHash,
                        creationDate = request.creationDate,
                        responseDate = request.responseDate,
                        requestApproved = request.requestApproved ?: false,
                        originUrl = request.originUrl,
                        fingerprint = updateResult.authRequest.fingerprint,
                    )
                }
                .fold(
                    onFailure = { emit(AuthRequestUpdatesResult.Error) },
                    onSuccess = { updateAuthRequest ->
                        when {
                            updateAuthRequest.requestApproved -> {
                                isComplete = true
                                emit(AuthRequestUpdatesResult.Approved)
                            }

                            !updateAuthRequest.requestApproved &&
                                updateAuthRequest.responseDate != null -> {
                                isComplete = true
                                emit(AuthRequestUpdatesResult.Declined)
                            }

                            updateAuthRequest
                                .creationDate
                                .toInstant()
                                .plusMillis(PASSWORDLESS_NOTIFICATION_TIMEOUT_MILLIS)
                                .isBefore(clock.instant()) -> {
                                isComplete = true
                                emit(AuthRequestUpdatesResult.Expired)
                            }

                            else -> {
                                emit(AuthRequestUpdatesResult.Update(updateAuthRequest))
                            }
                        }
                    },
                )
        }
    }

    override fun getAuthRequestByFingerprintFlow(
        fingerprint: String,
    ): Flow<AuthRequestUpdatesResult> = getAuthRequest {
        when (val authRequestsResult = getAuthRequests()) {
            AuthRequestsResult.Error -> AuthRequestUpdatesResult.Error
            is AuthRequestsResult.Success -> {
                authRequestsResult
                    .authRequests
                    .firstOrNull { it.fingerprint == fingerprint }
                    ?.let { AuthRequestUpdatesResult.Update(it) }
                    ?: AuthRequestUpdatesResult.Error
            }
        }
    }

    override fun getAuthRequestByIdFlow(
        requestId: String,
    ): Flow<AuthRequestUpdatesResult> = getAuthRequest {
        authRequestsService
            .getAuthRequest(requestId)
            .map { request ->
                when (val result = getFingerprintPhrase(request.publicKey)) {
                    is UserFingerprintResult.Error -> null
                    is UserFingerprintResult.Success -> AuthRequest(
                        id = request.id,
                        publicKey = request.publicKey,
                        platform = request.platform,
                        ipAddress = request.ipAddress,
                        key = request.key,
                        masterPasswordHash = request.masterPasswordHash,
                        creationDate = request.creationDate,
                        responseDate = request.responseDate,
                        requestApproved = request.requestApproved ?: false,
                        originUrl = request.originUrl,
                        fingerprint = result.fingerprint,
                    )
                }
            }
            .fold(
                onFailure = { AuthRequestUpdatesResult.Error },
                onSuccess = { authRequest ->
                    authRequest
                        ?.let { AuthRequestUpdatesResult.Update(it) }
                        ?: AuthRequestUpdatesResult.Error
                },
            )
    }

    override suspend fun getAuthRequests(): AuthRequestsResult =
        authRequestsService
            .getAuthRequests()
            .fold(
                onFailure = { AuthRequestsResult.Error },
                onSuccess = { response ->
                    AuthRequestsResult.Success(
                        authRequests = response.authRequests.mapNotNull { request ->
                            when (val result = getFingerprintPhrase(request.publicKey)) {
                                is UserFingerprintResult.Error -> null
                                is UserFingerprintResult.Success -> AuthRequest(
                                    id = request.id,
                                    publicKey = request.publicKey,
                                    platform = request.platform,
                                    ipAddress = request.ipAddress,
                                    key = request.key,
                                    masterPasswordHash = request.masterPasswordHash,
                                    creationDate = request.creationDate,
                                    responseDate = request.responseDate,
                                    requestApproved = request.requestApproved ?: false,
                                    originUrl = request.originUrl,
                                    fingerprint = result.fingerprint,
                                )
                            }
                        },
                    )
                },
            )

    override suspend fun updateAuthRequest(
        requestId: String,
        masterPasswordHash: String?,
        publicKey: String,
        isApproved: Boolean,
    ): AuthRequestResult {
        val userId = activeUserId ?: return AuthRequestResult.Error
        return vaultSdkSource
            .getAuthRequestKey(
                publicKey = publicKey,
                userId = userId,
            )
            .flatMap {
                authRequestsService.updateAuthRequest(
                    requestId = requestId,
                    key = it,
                    deviceId = authDiskSource.uniqueAppId,
                    masterPasswordHash = null,
                    isApproved = isApproved,
                )
            }
            .map { request ->
                AuthRequestResult.Success(
                    authRequest = AuthRequest(
                        id = request.id,
                        publicKey = request.publicKey,
                        platform = request.platform,
                        ipAddress = request.ipAddress,
                        key = request.key,
                        masterPasswordHash = request.masterPasswordHash,
                        creationDate = request.creationDate,
                        responseDate = request.responseDate,
                        requestApproved = request.requestApproved ?: false,
                        originUrl = request.originUrl,
                        fingerprint = "",
                    ),
                )
            }
            .fold(
                onFailure = { AuthRequestResult.Error },
                onSuccess = { it },
            )
    }

    /**
     * Attempts to create a new auth request for the given email and returns a [NewAuthRequestData]
     * with the [AuthRequest] and [AuthRequestResponse].
     */
    private suspend fun createNewAuthRequest(
        email: String,
    ): Result<NewAuthRequestData> =
        authSdkSource
            .getNewAuthRequest(email)
            .flatMap { authRequestResponse ->
                newAuthRequestService
                    .createAuthRequest(
                        email = email,
                        publicKey = authRequestResponse.publicKey,
                        deviceId = authDiskSource.uniqueAppId,
                        accessCode = authRequestResponse.accessCode,
                        fingerprint = authRequestResponse.fingerprint,
                    )
                    .map { request ->
                        AuthRequest(
                            id = request.id,
                            publicKey = request.publicKey,
                            platform = request.platform,
                            ipAddress = request.ipAddress,
                            key = request.key,
                            masterPasswordHash = request.masterPasswordHash,
                            creationDate = request.creationDate,
                            responseDate = request.responseDate,
                            requestApproved = request.requestApproved ?: false,
                            originUrl = request.originUrl,
                            fingerprint = authRequestResponse.fingerprint,
                        )
                    }
                    .map { NewAuthRequestData(it, authRequestResponse) }
            }

    private suspend fun getFingerprintPhrase(
        publicKey: String,
    ): UserFingerprintResult {
        val profile = authDiskSource.userState?.activeAccount?.profile
            ?: return UserFingerprintResult.Error

        return authSdkSource
            .getUserFingerprint(
                email = profile.email,
                publicKey = publicKey,
            )
            .fold(
                onFailure = { UserFingerprintResult.Error },
                onSuccess = { UserFingerprintResult.Success(it) },
            )
    }
}

/**
 * Wrapper class for the [AuthRequest] and [AuthRequestResponse] data.
 */
private data class NewAuthRequestData(
    val authRequest: AuthRequest,
    val authRequestResponse: AuthRequestResponse,
)
