package com.x8bit.bitwarden.ui.vault.feature.item

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bitwarden.core.CipherView
import com.x8bit.bitwarden.R
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.BreachCountResult
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.platform.manager.clipboard.BitwardenClipboardManager
import com.x8bit.bitwarden.data.platform.repository.model.DataState
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.data.vault.repository.model.DeleteCipherResult
import com.x8bit.bitwarden.data.vault.repository.model.RestoreCipherResult
import com.x8bit.bitwarden.data.vault.repository.model.VerifyPasswordResult
import com.x8bit.bitwarden.ui.platform.base.BaseViewModel
import com.x8bit.bitwarden.ui.platform.base.util.Text
import com.x8bit.bitwarden.ui.platform.base.util.asText
import com.x8bit.bitwarden.ui.platform.base.util.concat
import com.x8bit.bitwarden.ui.vault.feature.item.util.toViewState
import com.x8bit.bitwarden.ui.vault.model.VaultCardBrand
import com.x8bit.bitwarden.ui.vault.model.VaultLinkedFieldType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import javax.inject.Inject

private const val KEY_STATE = "state"

/**
 * ViewModel responsible for handling user interactions in the vault item screen
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class VaultItemViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val clipboardManager: BitwardenClipboardManager,
    private val authRepository: AuthRepository,
    private val vaultRepository: VaultRepository,
) : BaseViewModel<VaultItemState, VaultItemEvent, VaultItemAction>(
    // We load the state from the savedStateHandle for testing purposes.
    initialState = savedStateHandle[KEY_STATE] ?: VaultItemState(
        vaultItemId = VaultItemArgs(savedStateHandle).vaultItemId,
        viewState = VaultItemState.ViewState.Loading,
        dialog = null,
    ),
) {

    //region Initialization and Overrides
    init {
        combine(
            vaultRepository.getVaultItemStateFlow(state.vaultItemId),
            authRepository.userStateFlow,
        ) { cipherViewState, userState ->
            VaultItemAction.Internal.VaultDataReceive(
                userState = userState,
                vaultDataState = cipherViewState,
            )
        }
            .onEach(::sendAction)
            .launchIn(viewModelScope)
    }

    override fun handleAction(action: VaultItemAction) {
        when (action) {
            is VaultItemAction.ItemType.Login -> handleLoginTypeActions(action)
            is VaultItemAction.ItemType.Card -> handleCardTypeActions(action)
            is VaultItemAction.Common -> handleCommonActions(action)
            is VaultItemAction.Internal -> handleInternalAction(action)
        }
    }
    //endregion Initialization and Overrides

    //region Common Handlers

    private fun handleCommonActions(action: VaultItemAction.Common) {
        when (action) {
            is VaultItemAction.Common.CloseClick -> handleCloseClick()
            is VaultItemAction.Common.DismissDialogClick -> handleDismissDialogClick()
            is VaultItemAction.Common.EditClick -> handleEditClick()
            is VaultItemAction.Common.MasterPasswordSubmit -> handleMasterPasswordSubmit(action)
            is VaultItemAction.Common.RefreshClick -> handleRefreshClick()
            is VaultItemAction.Common.CopyCustomHiddenFieldClick -> {
                handleCopyCustomHiddenFieldClick(action)
            }

            is VaultItemAction.Common.CopyCustomTextFieldClick -> {
                handleCopyCustomTextFieldClick(action)
            }

            is VaultItemAction.Common.HiddenFieldVisibilityClicked -> {
                handleHiddenFieldVisibilityClicked(action)
            }

            is VaultItemAction.Common.AttachmentsClick -> handleAttachmentsClick()
            is VaultItemAction.Common.CloneClick -> handleCloneClick()
            is VaultItemAction.Common.MoveToOrganizationClick -> handleMoveToOrganizationClick()
            is VaultItemAction.Common.ConfirmDeleteClick -> handleConfirmDeleteClick()
            is VaultItemAction.Common.ConfirmRestoreClick -> handleConfirmRestoreClick()
        }
    }

    private fun handleCloseClick() {
        sendEvent(VaultItemEvent.NavigateBack)
    }

    private fun handleDismissDialogClick() {
        mutableStateFlow.update { it.copy(dialog = null) }
    }

    private fun handleEditClick() {
        onContent { content ->
            if (content.common.requiresReprompt) {
                mutableStateFlow.update {
                    it.copy(dialog = VaultItemState.DialogState.MasterPasswordDialog)
                }
                return@onContent
            }
            sendEvent(
                VaultItemEvent.NavigateToAddEdit(
                    itemId = state.vaultItemId,
                    isClone = false,
                ),
            )
        }
    }

    private fun handleMasterPasswordSubmit(action: VaultItemAction.Common.MasterPasswordSubmit) {
        mutableStateFlow.update {
            it.copy(dialog = VaultItemState.DialogState.Loading(R.string.loading.asText()))
        }
        viewModelScope.launch {
            @Suppress("MagicNumber")
            delay(2_000)
            // TODO: Actually verify the password (BIT-1213)
            sendAction(
                VaultItemAction.Internal.VerifyPasswordReceive(
                    VerifyPasswordResult.Success(isVerified = true),
                ),
            )
            sendEvent(
                VaultItemEvent.ShowToast("Password verification not yet implemented.".asText()),
            )
        }
    }

    private fun handleRefreshClick() {
        // No need to update the view state, the vault repo will emit a new state during this time
        vaultRepository.sync()
    }

    private fun handleCopyCustomHiddenFieldClick(
        action: VaultItemAction.Common.CopyCustomHiddenFieldClick,
    ) {
        onContent { content ->
            if (content.common.requiresReprompt) {
                mutableStateFlow.update {
                    it.copy(dialog = VaultItemState.DialogState.MasterPasswordDialog)
                }
                return@onContent
            }
            clipboardManager.setText(text = action.field)
        }
    }

    private fun handleCopyCustomTextFieldClick(
        action: VaultItemAction.Common.CopyCustomTextFieldClick,
    ) {
        clipboardManager.setText(text = action.field)
    }

    private fun handleHiddenFieldVisibilityClicked(
        action: VaultItemAction.Common.HiddenFieldVisibilityClicked,
    ) {
        onContent { content ->
            if (content.common.requiresReprompt) {
                mutableStateFlow.update {
                    it.copy(dialog = VaultItemState.DialogState.MasterPasswordDialog)
                }
                return@onContent
            }
            mutableStateFlow.update { currentState ->
                currentState.copy(
                    viewState = content.copy(
                        common = content.common.copy(
                            customFields = content.common.customFields.map { customField ->
                                if (customField == action.field) {
                                    action.field.copy(isVisible = action.isVisible)
                                } else {
                                    customField
                                }
                            },
                        ),
                    ),
                )
            }
        }
    }

    private fun handleAttachmentsClick() {
        sendEvent(VaultItemEvent.NavigateToAttachments(itemId = state.vaultItemId))
    }

    private fun handleCloneClick() {
        sendEvent(
            VaultItemEvent.NavigateToAddEdit(
                itemId = state.vaultItemId,
                isClone = true,
            ),
        )
    }

    private fun handleMoveToOrganizationClick() {
        sendEvent(VaultItemEvent.NavigateToMoveToOrganization(itemId = state.vaultItemId))
    }

    private fun handleConfirmDeleteClick() {
        mutableStateFlow.update {
            it.copy(
                dialog = VaultItemState.DialogState.Loading(
                    R.string.soft_deleting.asText(),
                ),
            )
        }
        onContent { content ->
            content
                .common
                .currentCipher
                ?.let { cipher ->
                    viewModelScope.launch {
                        trySendAction(
                            VaultItemAction.Internal.DeleteCipherReceive(
                                result = vaultRepository.softDeleteCipher(
                                    cipherId = state.vaultItemId,
                                    cipherView = cipher,
                                ),
                            ),
                        )
                    }
            }
        }
    }

    private fun handleConfirmRestoreClick() {
        mutableStateFlow.update {
            it.copy(
                dialog = VaultItemState.DialogState.Loading(
                    R.string.restoring.asText(),
                ),
            )
        }
        onContent { content ->
            content
                .common
                .currentCipher
                ?.let { cipher ->
                    viewModelScope.launch {
                        trySendAction(
                            VaultItemAction.Internal.RestoreCipherReceive(
                                result = vaultRepository.restoreCipher(
                                    cipherId = state.vaultItemId,
                                    cipherView = cipher,
                                ),
                            ),
                        )
                    }
                }
        }
    }

    //endregion Common Handlers

    //region Login Type Handlers

    private fun handleLoginTypeActions(action: VaultItemAction.ItemType.Login) {
        when (action) {
            is VaultItemAction.ItemType.Login.CheckForBreachClick -> {
                handleCheckForBreachClick()
            }

            is VaultItemAction.ItemType.Login.CopyPasswordClick -> {
                handleCopyPasswordClick()
            }

            is VaultItemAction.ItemType.Login.CopyUriClick -> {
                handleCopyUriClick(action)
            }

            is VaultItemAction.ItemType.Login.CopyUsernameClick -> {
                handleCopyUsernameClick()
            }

            is VaultItemAction.ItemType.Login.LaunchClick -> {
                handleLaunchClick(action)
            }

            is VaultItemAction.ItemType.Login.PasswordHistoryClick -> {
                handlePasswordHistoryClick()
            }

            is VaultItemAction.ItemType.Login.PasswordVisibilityClicked -> {
                handlePasswordVisibilityClicked(action)
            }
        }
    }

    private fun handleCheckForBreachClick() {
        onLoginContent { _, login ->
            val password = requireNotNull(login.passwordData?.password)
            mutableStateFlow.update {
                it.copy(dialog = VaultItemState.DialogState.Loading(R.string.loading.asText()))
            }
            viewModelScope.launch {
                val result = authRepository.getPasswordBreachCount(password = password)
                sendAction(VaultItemAction.Internal.PasswordBreachReceive(result))
            }
        }
    }

    private fun handleCopyPasswordClick() {
        onLoginContent { content, login ->
            if (content.common.requiresReprompt) {
                mutableStateFlow.update {
                    it.copy(dialog = VaultItemState.DialogState.MasterPasswordDialog)
                }
                return@onLoginContent
            }
            val password = requireNotNull(login.passwordData?.password)
            clipboardManager.setText(text = password)
        }
    }

    private fun handleCopyUriClick(action: VaultItemAction.ItemType.Login.CopyUriClick) {
        clipboardManager.setText(text = action.uri)
    }

    private fun handleCopyUsernameClick() {
        onLoginContent { content, login ->
            if (content.common.requiresReprompt) {
                mutableStateFlow.update {
                    it.copy(dialog = VaultItemState.DialogState.MasterPasswordDialog)
                }
                return@onLoginContent
            }
            val username = requireNotNull(login.username)
            clipboardManager.setText(text = username)
        }
    }

    private fun handleLaunchClick(
        action: VaultItemAction.ItemType.Login.LaunchClick,
    ) {
        sendEvent(VaultItemEvent.NavigateToUri(action.uri))
    }

    private fun handlePasswordHistoryClick() {
        onContent { content ->
            if (content.common.requiresReprompt) {
                mutableStateFlow.update {
                    it.copy(dialog = VaultItemState.DialogState.MasterPasswordDialog)
                }
                return@onContent
            }
            sendEvent(VaultItemEvent.NavigateToPasswordHistory(state.vaultItemId))
        }
    }

    private fun handlePasswordVisibilityClicked(
        action: VaultItemAction.ItemType.Login.PasswordVisibilityClicked,
    ) {
        onLoginContent { content, login ->
            if (content.common.requiresReprompt) {
                mutableStateFlow.update {
                    it.copy(dialog = VaultItemState.DialogState.MasterPasswordDialog)
                }
                return@onLoginContent
            }
            mutableStateFlow.update { currentState ->
                currentState.copy(
                    viewState = content.copy(
                        type = login.copy(
                            passwordData = login.passwordData?.copy(
                                isVisible = action.isVisible,
                            ),
                        ),
                    ),
                )
            }
        }
    }

    //endregion Login Type Handlers

    //region Card Type Handlers

    private fun handleCardTypeActions(action: VaultItemAction.ItemType.Card) {
        when (action) {
            VaultItemAction.ItemType.Card.CopyNumberClick -> handleCopyNumberClick()
            VaultItemAction.ItemType.Card.CopySecurityCodeClick -> handleCopySecurityCodeClick()
        }
    }

    private fun handleCopyNumberClick() {
        onCardContent { content, card ->
            if (content.common.requiresReprompt) {
                mutableStateFlow.update {
                    it.copy(dialog = VaultItemState.DialogState.MasterPasswordDialog)
                }
                return@onCardContent
            }
            val number = requireNotNull(card.number)
            clipboardManager.setText(text = number)
        }
    }

    private fun handleCopySecurityCodeClick() {
        onCardContent { content, card ->
            if (content.common.requiresReprompt) {
                mutableStateFlow.update {
                    it.copy(dialog = VaultItemState.DialogState.MasterPasswordDialog)
                }
                return@onCardContent
            }
            val securityCode = requireNotNull(card.securityCode)
            clipboardManager.setText(text = securityCode)
        }
    }

    //endregion Card Type Handlers

    //region Internal Type Handlers

    private fun handleInternalAction(action: VaultItemAction.Internal) {
        when (action) {
            is VaultItemAction.Internal.PasswordBreachReceive -> handlePasswordBreachReceive(action)
            is VaultItemAction.Internal.VaultDataReceive -> handleVaultDataReceive(action)
            is VaultItemAction.Internal.VerifyPasswordReceive -> handleVerifyPasswordReceive(action)
            is VaultItemAction.Internal.DeleteCipherReceive -> handleDeleteCipherReceive(action)
            is VaultItemAction.Internal.RestoreCipherReceive -> handleRestoreCipherReceive(action)
        }
    }

    private fun handlePasswordBreachReceive(
        action: VaultItemAction.Internal.PasswordBreachReceive,
    ) {
        val message = when (val result = action.result) {
            BreachCountResult.Error -> R.string.generic_error_message.asText()
            is BreachCountResult.Success -> {
                if (result.breachCount > 0) {
                    R.string.password_exposed.asText(result.breachCount)
                } else {
                    R.string.password_safe.asText()
                }
            }
        }
        mutableStateFlow.update {
            it.copy(dialog = VaultItemState.DialogState.Generic(message = message))
        }
    }

    private fun handleVaultDataReceive(action: VaultItemAction.Internal.VaultDataReceive) {
        // Leave the current data alone if there is no UserState; we are in the process of logging
        // out.
        val userState = action.userState ?: return

        when (val vaultDataState = action.vaultDataState) {
            is DataState.Error -> {
                mutableStateFlow.update {
                    it.copy(
                        viewState = VaultItemState.ViewState.Error(
                            message = R.string.generic_error_message.asText(),
                        ),
                    )
                }
            }

            is DataState.Loaded -> {
                mutableStateFlow.update {
                    it.copy(
                        viewState = vaultDataState.data
                            ?.toViewState(isPremiumUser = userState.activeAccount.isPremium)
                            ?: VaultItemState.ViewState.Error(
                                message = R.string.generic_error_message.asText(),
                            ),
                    )
                }
            }

            DataState.Loading -> {
                mutableStateFlow.update {
                    it.copy(viewState = VaultItemState.ViewState.Loading)
                }
            }

            is DataState.NoNetwork -> {
                mutableStateFlow.update {
                    it.copy(
                        viewState = VaultItemState.ViewState.Error(
                            message = R.string.internet_connection_required_title
                                .asText()
                                .concat(R.string.internet_connection_required_message.asText()),
                        ),
                    )
                }
            }

            is DataState.Pending -> {
                mutableStateFlow.update {
                    it.copy(
                        viewState = vaultDataState.data
                            ?.toViewState(isPremiumUser = userState.activeAccount.isPremium)
                            ?: VaultItemState.ViewState.Error(
                                message = R.string.generic_error_message.asText(),
                            ),
                    )
                }
            }
        }
    }

    private fun handleVerifyPasswordReceive(
        action: VaultItemAction.Internal.VerifyPasswordReceive,
    ) {
        when (val result = action.result) {
            VerifyPasswordResult.Error -> {
                mutableStateFlow.update {
                    it.copy(
                        dialog = VaultItemState.DialogState.Generic(
                            message = R.string.invalid_master_password.asText(),
                        ),
                    )
                }
            }

            is VerifyPasswordResult.Success -> {
                onContent { content ->
                    mutableStateFlow.update {
                        it.copy(
                            dialog = null,
                            viewState = content.copy(
                                common = content.common.copy(
                                    requiresReprompt = !result.isVerified,
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun handleDeleteCipherReceive(action: VaultItemAction.Internal.DeleteCipherReceive) {
        when (action.result) {
            DeleteCipherResult.Error -> {
                mutableStateFlow.update {
                    it.copy(
                        dialog = VaultItemState.DialogState.Generic(
                            message = R.string.generic_error_message.asText(),
                        ),
                    )
                }
            }
            DeleteCipherResult.Success -> {
                mutableStateFlow.update { it.copy(dialog = null) }
                sendEvent(VaultItemEvent.ShowToast(message = R.string.item_soft_deleted.asText()))
                sendEvent(VaultItemEvent.NavigateBack)
            }
        }
    }

    private fun handleRestoreCipherReceive(action: VaultItemAction.Internal.RestoreCipherReceive) {
        when (action.result) {
            RestoreCipherResult.Error -> {
                mutableStateFlow.update {
                    it.copy(
                        dialog = VaultItemState.DialogState.Generic(
                            message = R.string.generic_error_message.asText(),
                        ),
                    )
                }
            }

            RestoreCipherResult.Success -> {
                mutableStateFlow.update { it.copy(dialog = null) }
                sendEvent(VaultItemEvent.ShowToast(message = R.string.item_restored.asText()))
                sendEvent(VaultItemEvent.NavigateBack)
            }
        }
    }

    //endregion Internal Type Handlers

    private inline fun onContent(
        crossinline block: (VaultItemState.ViewState.Content) -> Unit,
    ) {
        (state.viewState as? VaultItemState.ViewState.Content)?.let(block)
    }

    private inline fun onLoginContent(
        crossinline block: (
            VaultItemState.ViewState.Content,
            VaultItemState.ViewState.Content.ItemType.Login,
        ) -> Unit,
    ) {
        (state.viewState as? VaultItemState.ViewState.Content)
            ?.let { content ->
                (content.type as? VaultItemState.ViewState.Content.ItemType.Login)
                    ?.let { loginContent ->
                        block(content, loginContent)
                    }
            }
    }

    private inline fun onCardContent(
        crossinline block: (
            VaultItemState.ViewState.Content,
            VaultItemState.ViewState.Content.ItemType.Card,
        ) -> Unit,
    ) {
        (state.viewState as? VaultItemState.ViewState.Content)
            ?.let { content ->
                (content.type as? VaultItemState.ViewState.Content.ItemType.Card)
                    ?.let { loginContent ->
                        block(content, loginContent)
                    }
            }
    }
}

/**
 * Represents the state for viewing an item in the vault.
 */
@Parcelize
data class VaultItemState(
    val vaultItemId: String,
    val viewState: ViewState,
    val dialog: DialogState?,
) : Parcelable {

    /**
     * Whether or not the cipher has been deleted.
     */
    val isCipherDeleted: Boolean
        get() = (viewState as? ViewState.Content)
            ?.common
            ?.currentCipher
            ?.deletedDate != null

    /**
     * Whether or not the fab is visible.
     */
    val isFabVisible: Boolean
        get() = viewState is ViewState.Content && !isCipherDeleted

    /**
     * Represents the specific view states for the [VaultItemScreen].
     */
    sealed class ViewState : Parcelable {
        /**
         * Represents an error state for the [VaultItemScreen].
         */
        @Parcelize
        data class Error(
            val message: Text,
        ) : ViewState()

        /**
         * Loading state for the [VaultItemScreen], signifying that the content is being processed.
         */
        @Parcelize
        data object Loading : ViewState()

        /**
         * Represents a loaded content state for the [VaultItemScreen].
         */
        @Parcelize
        data class Content(
            val common: Common,
            val type: ItemType,
        ) : ViewState() {

            /**
             * Content data that is common for all item types.
             *
             * @property name The name of the item.
             * @property lastUpdated A formatted date string indicating when the item was last
             * updated.
             * @property notes Contains general notes taken by the user.
             * @property customFields A list of custom fields that user has added.
             * @property requiresReprompt Indicates if a master password prompt is required to view
             * secure fields.
             * @property currentCipher The cipher that is currently being viewed (nullable).
             */
            @Parcelize
            data class Common(
                val name: String,
                val lastUpdated: String,
                val notes: String?,
                val customFields: List<Custom>,
                val requiresReprompt: Boolean,
                @IgnoredOnParcel
                val currentCipher: CipherView? = null,
            ) : Parcelable {

                /**
                 * Represents a custom field, TextField, HiddenField, BooleanField, or LinkedField.
                 */
                sealed class Custom : Parcelable {
                    /**
                     * Represents the data for displaying a custom text field.
                     */
                    @Parcelize
                    data class TextField(
                        val name: String,
                        val value: String,
                        val isCopyable: Boolean,
                    ) : Custom()

                    /**
                     * Represents the data for displaying a custom hidden text field.
                     */
                    @Parcelize
                    data class HiddenField(
                        val name: String,
                        val value: String,
                        val isCopyable: Boolean,
                        val isVisible: Boolean,
                    ) : Custom()

                    /**
                     * Represents the data for displaying a custom boolean property field.
                     */
                    @Parcelize
                    data class BooleanField(
                        val name: String,
                        val value: Boolean,
                    ) : Custom()

                    /**
                     * Represents the data for displaying a custom linked field.
                     */
                    @Parcelize
                    data class LinkedField(
                        val vaultLinkedFieldType: VaultLinkedFieldType,
                        val name: String,
                    ) : Custom()
                }
            }

            /**
             * Content data specific to an item type.
             */
            @Parcelize
            sealed class ItemType : Parcelable {

                /**
                 * Represents the `Login` item type.
                 *
                 * @property username The username required for the login item.
                 * @property passwordData The password required for the login item.
                 * @property passwordHistoryCount An integer indicating how many times the password
                 * has been changed.
                 * @property uris The URI associated with the login item.
                 * @property passwordRevisionDate An optional string indicating the last time the
                 * password was changed.
                 * @property totp The optional TOTP string value to be displayed.
                 * @property isPremiumUser Indicates if the user has subscribed to a premium
                 * account.
                 */
                @Parcelize
                data class Login(
                    val username: String?,
                    val passwordData: PasswordData?,
                    val passwordHistoryCount: Int?,
                    val uris: List<UriData>,
                    val passwordRevisionDate: String?,
                    val totp: String?,
                    val isPremiumUser: Boolean,
                ) : ItemType() {

                    /**
                     * A wrapper for the password data.
                     *
                     * @property password The password itself.
                     * @property isVisible Whether or not it is currently visible.
                     * @property canViewPassword Indicates whether the current user can view and
                     * copy passwords associated with the login item.
                     */
                    @Parcelize
                    data class PasswordData(
                        val password: String,
                        val isVisible: Boolean,
                        val canViewPassword: Boolean,
                    ) : Parcelable

                    /**
                     * A wrapper for URI data, including the [uri] itself and whether it is
                     * copyable and launch-able.
                     */
                    @Parcelize
                    data class UriData(
                        val uri: String,
                        val isCopyable: Boolean,
                        val isLaunchable: Boolean,
                    ) : Parcelable
                }

                /**
                 * Represents the `SecureNote` item type.
                 */
                data object SecureNote : ItemType()

                /**
                 * Represents the `Identity` item type.
                 *
                 * @property identityName The name for the identity.
                 * @property username The username for the identity.
                 * @property company The company associated with the identity.
                 * @property ssn The SSN for the identity.
                 * @property passportNumber The passport number for the identity.
                 * @property licenseNumber The license number for the identity.
                 * @property email The email for the identity.
                 * @property phone The phone number for the identity.
                 * @property address The address for the identity.
                 */
                data class Identity(
                    val identityName: String?,
                    val username: String?,
                    val company: String?,
                    val ssn: String?,
                    val passportNumber: String?,
                    val licenseNumber: String?,
                    val email: String?,
                    val phone: String?,
                    val address: String?,
                ) : ItemType()

                /**
                 * Represents the `Card` item type.
                 *
                 * @property cardholderName The cardholder name for the card.
                 * @property number The number for the card.
                 * @property brand The brand for the card.
                 * @property expiration The expiration for the card.
                 * @property securityCode The securityCode for the card.
                 */
                data class Card(
                    val cardholderName: String?,
                    val number: String?,
                    val brand: VaultCardBrand?,
                    val expiration: String?,
                    val securityCode: String?,
                ) : ItemType()
            }
        }
    }

    /**
     * Displays a dialog.
     */
    sealed class DialogState : Parcelable {

        /**
         * Displays a generic dialog to the user.
         */
        @Parcelize
        data class Generic(
            val message: Text,
        ) : DialogState()

        /**
         * Displays the loading dialog to the user with a message.
         */
        @Parcelize
        data class Loading(
            val message: Text,
        ) : DialogState()

        /**
         * Displays the master password dialog to the user.
         */
        @Parcelize
        data object MasterPasswordDialog : DialogState()
    }
}

/**
 * Represents a set of events related view a vault item.
 */
sealed class VaultItemEvent {
    /**
     * Navigates back.
     */
    data object NavigateBack : VaultItemEvent()

    /**
     * Navigates to the edit screen.
     */
    data class NavigateToAddEdit(
        val itemId: String,
        val isClone: Boolean,
    ) : VaultItemEvent()

    /**
     * Navigates to the password history screen.
     */
    data class NavigateToPasswordHistory(
        val itemId: String,
    ) : VaultItemEvent()

    /**
     * Launches the external URI.
     */
    data class NavigateToUri(
        val uri: String,
    ) : VaultItemEvent()

    /**
     * Navigates to the attachments screen.
     */
    data class NavigateToAttachments(
        val itemId: String,
    ) : VaultItemEvent()

    /**
     * Navigates to the move to organization screen.
     */
    data class NavigateToMoveToOrganization(
        val itemId: String,
    ) : VaultItemEvent()

    /**
     * Places the given [message] in your clipboard.
     */
    data class ShowToast(
        val message: Text,
    ) : VaultItemEvent()
}

/**
 * Represents a set of actions related to viewing a vault item.
 * Each subclass of this sealed class denotes a distinct action that can be taken.
 */
sealed class VaultItemAction {

    /**
     * Represents actions common across all item types.
     */
    sealed class Common : VaultItemAction() {

        /**
         * The user has clicked the close button.
         */
        data object CloseClick : Common()

        /**
         * The user has confirmed to deleted the cipher.
         */
        data object ConfirmDeleteClick : Common()

        /**
         * The user has confirmed to restore the cipher.
         */
        data object ConfirmRestoreClick : Common()

        /**
         * The user has clicked to dismiss the dialog.
         */
        data object DismissDialogClick : Common()

        /**
         * The user has clicked the edit button.
         */
        data object EditClick : Common()

        /**
         * The user has submitted their master password.
         */
        data class MasterPasswordSubmit(
            val masterPassword: String,
        ) : Common()

        /**
         * The user has clicked the refresh button.
         */
        data object RefreshClick : Common()

        /**
         * The user has clicked the copy button for a custom hidden field.
         */
        data class CopyCustomHiddenFieldClick(
            val field: String,
        ) : Common()

        /**
         * The user has clicked the copy button for a custom text field.
         */
        data class CopyCustomTextFieldClick(
            val field: String,
        ) : Common()

        /**
         * The user has clicked to display the hidden field.
         */
        data class HiddenFieldVisibilityClicked(
            val field: VaultItemState.ViewState.Content.Common.Custom.HiddenField,
            val isVisible: Boolean,
        ) : Common()

        /**
         * The user has clicked the attachments button.
         */
        data object AttachmentsClick : Common()

        /**
         * The user has clicked the clone button.
         */
        data object CloneClick : Common()

        /**
         * The user has clicked the move to organization button.
         */
        data object MoveToOrganizationClick : Common()
    }

    /**
     * Represents actions specific to an item type.
     */
    sealed class ItemType : VaultItemAction() {

        /**
         * Represents actions specific to the Login type.
         */
        sealed class Login : ItemType() {
            /**
             * The user has clicked the check for breach button.
             */
            data object CheckForBreachClick : Login()

            /**
             * The user has clicked the copy button for the password.
             */
            data object CopyPasswordClick : Login()

            /**
             * The user has clicked the copy button for a URI.
             */
            data class CopyUriClick(
                val uri: String,
            ) : Login()

            /**
             * The user has clicked the copy button for the username.
             */
            data object CopyUsernameClick : Login()

            /**
             * The user has clicked the launch button for a URI.
             */
            data class LaunchClick(
                val uri: String,
            ) : Login()

            /**
             * The user has clicked the password history text.
             */
            data object PasswordHistoryClick : Login()

            /**
             * The user has clicked to display the password.
             */
            data class PasswordVisibilityClicked(
                val isVisible: Boolean,
            ) : Login()
        }

        /**
         * Represents actions specific to the Card type.
         */
        sealed class Card : ItemType() {

            /**
             * The user has clicked the copy button for the number.
             */
            data object CopyNumberClick : Card()

            /**
             * The user has clicked the copy button for the security code.
             */
            data object CopySecurityCodeClick : Card()
        }
    }

    /**
     * Models actions that the [VaultItemViewModel] itself might send.
     */
    sealed class Internal : VaultItemAction() {
        /**
         * Indicates that the password breach results have been received.
         */
        data class PasswordBreachReceive(
            val result: BreachCountResult,
        ) : Internal()

        /**
         * Indicates that the vault item data has been received.
         */
        data class VaultDataReceive(
            val userState: UserState?,
            val vaultDataState: DataState<CipherView?>,
        ) : Internal()

        /**
         * Indicates that the verify password result has been received.
         */
        data class VerifyPasswordReceive(
            val result: VerifyPasswordResult,
        ) : Internal()

        /**
         * Indicates that the delete cipher result has been received.
         */
        data class DeleteCipherReceive(
            val result: DeleteCipherResult,
        ) : Internal()

        /**
         * Indicates that the restore cipher result has been received.
         */
        data class RestoreCipherReceive(
            val result: RestoreCipherResult,
        ) : Internal()
    }
}
