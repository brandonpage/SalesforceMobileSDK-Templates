package com.salesforce.mobilesyncexplorerkotlintemplate.contacts.detailscomponent

import com.salesforce.mobilesyncexplorerkotlintemplate.core.extensions.requireIsLocked
import com.salesforce.mobilesyncexplorerkotlintemplate.core.extensions.withLockDebug
import com.salesforce.mobilesyncexplorerkotlintemplate.core.repos.RepoOperationException
import com.salesforce.mobilesyncexplorerkotlintemplate.core.repos.SObjectSyncableRepo
import com.salesforce.mobilesyncexplorerkotlintemplate.core.repos.usecases.*
import com.salesforce.mobilesyncexplorerkotlintemplate.core.salesforceobject.SObjectRecord
import com.salesforce.mobilesyncexplorerkotlintemplate.core.salesforceobject.isLocallyDeleted
import com.salesforce.mobilesyncexplorerkotlintemplate.core.ui.state.*
import com.salesforce.mobilesyncexplorerkotlintemplate.model.contacts.ContactObject
import com.salesforce.mobilesyncexplorerkotlintemplate.model.contacts.ContactValidationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

interface ContactDetailsViewModel : ContactDetailsFieldChangeHandler, ContactDetailsUiEventHandler {
    val uiState: StateFlow<ContactDetailsUiState>

    // TODO this api could be improved by encapsulating all the "set" operations in sealed data classes.
    @Throws(ContactDetailsException::class)
    suspend fun setContactOrThrow(recordId: String?, isEditing: Boolean)

    @Throws(DataOperationActiveException::class)
    suspend fun discardChangesAndSetContactOrThrow(recordId: String?, isEditing: Boolean)
}

class DefaultContactDetailsViewModel(
    private val parentScope: CoroutineScope,
    private val contactsRepo: SObjectSyncableRepo<ContactObject>,
) : ContactDetailsViewModel {

    private val stateMutex = Mutex()
    private val dataOpDelegate = DataOperationDelegate()

    private val mutUiState: MutableStateFlow<ContactDetailsUiState> = MutableStateFlow(
        ContactDetailsUiState.NoContactSelected(
            dataOperationIsActive = false,
            doingInitialLoad = true,
            curDialogUiState = null
        )
    )

    override val uiState: StateFlow<ContactDetailsUiState> get() = mutUiState

    @Volatile
    private var curRecordId: String? = null

    @Volatile
    private lateinit var upstreamRecords: Map<String, SObjectRecord<ContactObject>>

    init {
        parentScope.launch(Dispatchers.Default) {
            contactsRepo.recordsById.collect { onNewRecords(it) }
        }
    }

    private suspend fun onNewRecords(
        newRecords: Map<String, SObjectRecord<ContactObject>>
    ) = stateMutex.withLockDebug {
        if (uiState.value.doingInitialLoad) {
            mutUiState.value = uiState.value.copy(doingInitialLoad = false)
        }

        upstreamRecords = newRecords

        val curId = curRecordId ?: return@withLockDebug
        val matchingRecord = newRecords[curId]

        // TODO This whole onNewRecords is buggy. I think a refactor is necessary, maybe having an internal state which includes the corresponding UI state so that the [curRecordId] doesn't get out of sync with the ui state?
        if (matchingRecord == null) {
            mutUiState.value = ContactDetailsUiState.NoContactSelected(
                dataOperationIsActive = dataOpDelegate.dataOperationIsActive,
                curDialogUiState = uiState.value.curDialogUiState
            )
            return@withLockDebug
        }

        mutUiState.value = when (val curState = uiState.value) {
            is ContactDetailsUiState.NoContactSelected -> {
                matchingRecord.sObject.buildViewingContactUiState(
                    uiSyncState = matchingRecord.localStatus.toUiSyncState(),
                    isEditingEnabled = false,
                    shouldScrollToErrorField = false,
                )
            }

            is ContactDetailsUiState.ViewingContactDetails -> {
                when {
                    // not editing, so simply update all fields to match upstream emission:
                    !curState.isEditingEnabled -> curState.copy(
                        firstNameField = matchingRecord.sObject.buildFirstNameField(),
                        lastNameField = matchingRecord.sObject.buildLastNameField(),
                        titleField = matchingRecord.sObject.buildTitleField(),
                        departmentField = matchingRecord.sObject.buildDepartmentField(),
                    )

                    /* TODO figure out how to reconcile when upstream was locally deleted but the user has
                        unsaved changes. Also applies to if upstream is permanently deleted. Reminder,
                        showing dialogs from data events is not allowed.
                        Idea: create a "snapshot" of the SO as soon as they begin editing, and only
                        prompt for choice upon clicking save. */
                    matchingRecord.localStatus.isLocallyDeleted && hasUnsavedChanges -> TODO()

                    // user is editing and there is no incompatible state, so no changes to state:
                    else -> curState
                }
            }
        }
    }

    @Throws(ContactDetailsException::class)
    override suspend fun setContactOrThrow(recordId: String?, isEditing: Boolean) {
        setContactOrThrow(recordId = recordId, isEditing = isEditing, forceDiscardChanges = false)
    }

    @Throws(DataOperationActiveException::class)
    override suspend fun discardChangesAndSetContactOrThrow(recordId: String?, isEditing: Boolean) {
        setContactOrThrow(recordId = recordId, isEditing = isEditing, forceDiscardChanges = true)
    }

    /**
     * This must not use launch if the thrown exceptions are to be correctly caught by the caller.
     */
    @Throws(ContactDetailsException::class)
    private suspend fun setContactOrThrow(
        recordId: String?,
        isEditing: Boolean,
        forceDiscardChanges: Boolean
    ) = stateMutex.withLockDebug {

        if (!this@DefaultContactDetailsViewModel::upstreamRecords.isInitialized || dataOpDelegate.dataOperationIsActive) {
            throw DataOperationActiveException(
                message = "Cannot change details content while there are data operations active."
            )
        }

        if (!forceDiscardChanges && hasUnsavedChanges) {
            throw HasUnsavedChangesException()
        }

        curRecordId = recordId

        if (recordId == null) {
            if (isEditing) {
                setStateForCreateNew()
            } else {
                mutUiState.value = ContactDetailsUiState.NoContactSelected(
                    dataOperationIsActive = dataOpDelegate.dataOperationIsActive,
                    curDialogUiState = uiState.value.curDialogUiState
                )
            }
        } else {
            val newRecord = upstreamRecords[recordId]
                ?: TODO("Did not find record with ID $recordId")

            mutUiState.value = when (val curState = uiState.value) {
                is ContactDetailsUiState.NoContactSelected -> newRecord.sObject.buildViewingContactUiState(
                    uiSyncState = newRecord.localStatus.toUiSyncState(),
                    isEditingEnabled = isEditing,
                    shouldScrollToErrorField = false,
                )
                is ContactDetailsUiState.ViewingContactDetails -> curState.copy(
                    firstNameField = newRecord.sObject.buildFirstNameField(),
                    lastNameField = newRecord.sObject.buildLastNameField(),
                    titleField = newRecord.sObject.buildTitleField(),
                    departmentField = newRecord.sObject.buildDepartmentField(),
                    isEditingEnabled = isEditing,
                    shouldScrollToErrorField = false
                )
            }
        }
    }

    private val hasUnsavedChanges: Boolean
        get() {
            stateMutex.requireIsLocked()
            return when (val curState = uiState.value) {
                is ContactDetailsUiState.ViewingContactDetails -> curRecordId?.let {
                    try {
                        curState.toSObjectOrThrow() != upstreamRecords[it]?.sObject
                    } catch (ex: ContactValidationException) {
                        // invalid field values means there are unsaved changes
                        true
                    }
                } ?: true // creating new contact, so assume changes are present
                is ContactDetailsUiState.NoContactSelected -> false
            }
        }

    override fun createClick() = launchWithStateLock {
        if (hasUnsavedChanges) {
            mutUiState.value = uiState.value.copy(
                curDialogUiState = DiscardChangesDialogUiState(

                    onDiscardChanges = {
                        launchWithStateLock {
                            setStateForCreateNew()
                            dismissCurDialog()
                        }
                    },

                    onKeepChanges = {
                        launchWithStateLock {
                            dismissCurDialog()
                        }
                    }
                )
            )
            return@launchWithStateLock
        }

        setStateForCreateNew()
    }

    private fun setStateForCreateNew() {
        stateMutex.requireIsLocked()

        curRecordId = null

        mutUiState.value = ContactDetailsUiState.ViewingContactDetails(
            firstNameField = ContactDetailsField.FirstName(
                fieldValue = null,
                onValueChange = ::onFirstNameChange
            ),
            lastNameField = ContactDetailsField.LastName(
                fieldValue = null,
                onValueChange = ::onLastNameChange
            ),
            titleField = ContactDetailsField.Title(
                fieldValue = null,
                onValueChange = ::onTitleChange
            ),
            departmentField = ContactDetailsField.Department(
                fieldValue = null,
                onValueChange = ::onDepartmentChange
            ),

            uiSyncState = SObjectUiSyncState.NotSaved,

            isEditingEnabled = true,
            dataOperationIsActive = dataOpDelegate.dataOperationIsActive,
            shouldScrollToErrorField = false,
            curDialogUiState = uiState.value.curDialogUiState
        )
    }

    override fun deleteClick() = launchWithStateLock {
        val targetRecordId = curRecordId ?: return@launchWithStateLock // no id => nothing to do

        mutUiState.value = uiState.value.copy(
            curDialogUiState = DeleteConfirmationDialogUiState(
                objIdToDelete = targetRecordId,
                objName = upstreamRecords[targetRecordId]?.sObject?.fullName,
                onCancelDelete = { launchWithStateLock { dismissCurDialog() } },
                onDeleteConfirm = {
                    dataOpDelegate.handleDataEvent(event = DetailsDataEvent.Delete(it))
                    launchWithStateLock { dismissCurDialog() }
                }
            )
        )
    }

    override fun undeleteClick() = launchWithStateLock {
        val targetRecordId = curRecordId ?: return@launchWithStateLock // no id => nothing to do

        mutUiState.value = uiState.value.copy(
            curDialogUiState = UndeleteConfirmationDialogUiState(
                objIdToUndelete = targetRecordId,
                objName = upstreamRecords[targetRecordId]?.sObject?.fullName,
                onCancelUndelete = { launchWithStateLock { dismissCurDialog() } },
                onUndeleteConfirm = {
                    dataOpDelegate.handleDataEvent(event = DetailsDataEvent.Undelete(it))
                    launchWithStateLock { dismissCurDialog() }
                },
            )
        )
    }

    private fun dismissCurDialog() {
        stateMutex.requireIsLocked()
        mutUiState.value = uiState.value.copy(curDialogUiState = null)
    }

    override fun editClick() = launchWithStateLock {
        mutUiState.value = when (val curState = uiState.value) {
            is ContactDetailsUiState.NoContactSelected -> curState
            is ContactDetailsUiState.ViewingContactDetails -> curState.copy(isEditingEnabled = true)
        }
    }

    override fun deselectContact() = launchWithStateLock {
        val viewingDetailsState = uiState.value as? ContactDetailsUiState.ViewingContactDetails
            ?: return@launchWithStateLock // already have no contact

        if (!hasUnsavedChanges) {
            mutUiState.value = ContactDetailsUiState.NoContactSelected(
                dataOperationIsActive = dataOpDelegate.dataOperationIsActive,
                curDialogUiState = viewingDetailsState.curDialogUiState
            )
            return@launchWithStateLock
        }

        mutUiState.value = viewingDetailsState.copy(
            curDialogUiState = DiscardChangesDialogUiState(
                onDiscardChanges = {
                    launchWithStateLock {
                        mutUiState.value = ContactDetailsUiState.NoContactSelected(
                            dataOperationIsActive = dataOpDelegate.dataOperationIsActive,
                            curDialogUiState = null
                        )
                    }
                },
                onKeepChanges = {
                    launchWithStateLock {
                        dismissCurDialog()
                    }
                }
            )
        )
    }

    override fun exitEditClick() = launchWithStateLock {
        val viewingContactDetails = uiState.value as? ContactDetailsUiState.ViewingContactDetails
            ?: return@launchWithStateLock // not editing, so nothing to do

        if (!hasUnsavedChanges) {
            mutUiState.value = viewingContactDetails.copy(isEditingEnabled = false)
            return@launchWithStateLock
        }

        val discardChangesDialog = DiscardChangesDialogUiState(
            onDiscardChanges = {
                launchWithStateLock {
                    val record = curRecordId?.let { upstreamRecords[it] }
                    mutUiState.value = record
                        ?.sObject
                        ?.buildViewingContactUiState(
                            uiSyncState = record.localStatus.toUiSyncState(),
                            isEditingEnabled = false,
                            shouldScrollToErrorField = false
                        )
                        ?: ContactDetailsUiState.NoContactSelected(
                            dataOperationIsActive = uiState.value.dataOperationIsActive,
                            curDialogUiState = null
                        )
                    dismissCurDialog()
                }
            },

            onKeepChanges = {
                launchWithStateLock {
                    dismissCurDialog()
                }
            }
        )

        mutUiState.value = viewingContactDetails.copy(
            curDialogUiState = discardChangesDialog
        )
    }

    override fun saveClick() = launchWithStateLock {
        // If not viewing details, we cannot build the SObject, so there is nothing to do
        val curState = uiState.value as? ContactDetailsUiState.ViewingContactDetails
            ?: return@launchWithStateLock

        val so = try {
            curState.toSObjectOrThrow()
        } catch (ex: Exception) {
            mutUiState.value = curState.copy(shouldScrollToErrorField = true)
            return@launchWithStateLock
        }

        val eventHandled = dataOpDelegate.handleDataEvent(
            event = curRecordId?.let { DetailsDataEvent.Update(id = it, so = so) }
                ?: DetailsDataEvent.Create(so = so)
        )

        if (!eventHandled) {
            // TODO should there be any prompt to the user for when a data op is already active?
        }
    }

    override fun onFirstNameChange(newFirstName: String) = launchWithStateLock {
        val curState = uiState.value as? ContactDetailsUiState.ViewingContactDetails
            ?: return@launchWithStateLock

        mutUiState.value = curState.copy(
            firstNameField = curState.firstNameField.copy(fieldValue = newFirstName)
        )
    }

    override fun onLastNameChange(newLastName: String) = launchWithStateLock {
        val curState = uiState.value as? ContactDetailsUiState.ViewingContactDetails
            ?: return@launchWithStateLock

        mutUiState.value = curState.copy(
            lastNameField = curState.lastNameField.copy(fieldValue = newLastName)
        )
    }

    override fun onTitleChange(newTitle: String) = launchWithStateLock {
        val curState = uiState.value as? ContactDetailsUiState.ViewingContactDetails
            ?: return@launchWithStateLock

        mutUiState.value = curState.copy(
            titleField = curState.titleField.copy(fieldValue = newTitle)
        )
    }

    override fun onDepartmentChange(newDepartment: String) = launchWithStateLock {
        val curState = uiState.value as? ContactDetailsUiState.ViewingContactDetails
            ?: return@launchWithStateLock

        mutUiState.value = curState.copy(
            departmentField = curState.departmentField.copy(fieldValue = newDepartment)
        )
    }

    private fun ContactDetailsUiState.ViewingContactDetails.toSObjectOrThrow() = ContactObject(
        firstName = firstNameField.fieldValue,
        lastName = lastNameField.fieldValue ?: "",
        title = titleField.fieldValue,
        department = departmentField.fieldValue
    )

    private fun ContactObject.buildViewingContactUiState(
        uiSyncState: SObjectUiSyncState,
        isEditingEnabled: Boolean,
        shouldScrollToErrorField: Boolean,
    ): ContactDetailsUiState.ViewingContactDetails {
        stateMutex.requireIsLocked()

        return ContactDetailsUiState.ViewingContactDetails(
            firstNameField = buildFirstNameField(),
            lastNameField = buildLastNameField(),
            titleField = buildTitleField(),
            departmentField = buildDepartmentField(),
            uiSyncState = uiSyncState,
            isEditingEnabled = isEditingEnabled,
            dataOperationIsActive = dataOpDelegate.dataOperationIsActive,
            shouldScrollToErrorField = shouldScrollToErrorField,
            curDialogUiState = uiState.value.curDialogUiState,
        )
    }

    private fun ContactObject.buildFirstNameField() = ContactDetailsField.FirstName(
        fieldValue = firstName,
        onValueChange = this@DefaultContactDetailsViewModel::onFirstNameChange
    )

    private fun ContactObject.buildLastNameField() = ContactDetailsField.LastName(
        fieldValue = lastName,
        onValueChange = this@DefaultContactDetailsViewModel::onLastNameChange
    )

    private fun ContactObject.buildTitleField() = ContactDetailsField.Title(
        fieldValue = title,
        onValueChange = this@DefaultContactDetailsViewModel::onTitleChange
    )

    private fun ContactObject.buildDepartmentField() = ContactDetailsField.Department(
        fieldValue = department,
        onValueChange = this@DefaultContactDetailsViewModel::onDepartmentChange
    )

    private fun launchWithStateLock(block: suspend CoroutineScope.() -> Unit) {
        parentScope.launch {
            stateMutex.withLockDebug { this.block() }
        }
    }

    private companion object {
        private const val TAG = "DefaultContactDetailsViewModel"
    }

    private inner class DataOperationDelegate {
        private val deleteUseCase = DeleteUseCase(repo = contactsRepo)
        private val undeleteUseCase = UndeleteUseCase(repo = contactsRepo)
        private val upsertUseCase = UpsertUseCase(repo = contactsRepo)
        private val mutDataOperationIsActive = AtomicBoolean(false)
        val dataOperationIsActive: Boolean get() = mutDataOperationIsActive.get()

        fun handleDataEvent(event: DetailsDataEvent): Boolean {
            val handlingEvent = mutDataOperationIsActive.compareAndSet(false, true)
            if (handlingEvent) {
                when (event) {
                    is DetailsDataEvent.Create -> launchSave(forId = null, so = event.so)
                    is DetailsDataEvent.Update -> launchSave(forId = event.id, so = event.so)
                    is DetailsDataEvent.Delete -> launchDelete(forId = event.id)
                    is DetailsDataEvent.Undelete -> launchUndelete(forId = event.id)
                }
            }

            return handlingEvent
        }

        private fun launchDelete(forId: String) = parentScope.launch {
            deleteUseCase(id = forId).collect { response ->
                when (response) {
                    is DeleteResponse.Started -> stateMutex.withLockDebug {
                        mutUiState.value = uiState.value.copy(dataOperationIsActive = true)
                    }

                    is DeleteResponse.DeleteSuccess -> {
                        stateMutex.withLockDebug {
                            mutUiState.value = response.record?.sObject?.buildViewingContactUiState(
                                uiSyncState = SObjectUiSyncState.Deleted,
                                isEditingEnabled = false,
                                shouldScrollToErrorField = false
                            ) ?: ContactDetailsUiState.NoContactSelected(
                                dataOperationIsActive = false,
                                curDialogUiState = uiState.value.curDialogUiState
                            )
                        }
                    }

                    is DeleteResponse.Finished -> {
                        stateMutex.withLockDebug {
                            mutUiState.value = uiState.value.copy(dataOperationIsActive = false)
                        }

                        mutDataOperationIsActive.set(false)

                        response.exception?.also { throw it } // WIP crash the app with full exception for now
                    }
                }
            }
        }

        private fun launchUndelete(forId: String) = parentScope.launch {
            undeleteUseCase(id = forId).collect { response ->
                when (response) {
                    is UndeleteResponse.Started -> stateMutex.withLockDebug {
                        mutUiState.value = uiState.value.copy(dataOperationIsActive = true)
                    }

                    is UndeleteResponse.UndeleteSuccess -> stateMutex.withLockDebug {
                        mutUiState.value = response.record.sObject.buildViewingContactUiState(
                            uiSyncState = response.record.localStatus.toUiSyncState(),
                            isEditingEnabled = false,
                            shouldScrollToErrorField = false
                        )
                    }

                    is UndeleteResponse.Finished -> {
                        stateMutex.withLockDebug {
                            mutUiState.value = uiState.value.copy(dataOperationIsActive = false)
                        }

                        mutDataOperationIsActive.set(false)

                        response.exception?.also { throw it } // WIP crash the app with full exception for now
                    }
                }
            }
        }

        private fun launchSave(forId: String?, so: ContactObject) = parentScope.launch {
            upsertUseCase(id = forId, so = so).collect { response ->
                when (response) {
                    is UpsertResponse.Started -> stateMutex.withLockDebug {
                        mutUiState.value = uiState.value.copy(dataOperationIsActive = true)
                    }

                    is UpsertResponse.UpsertSuccess -> {
                        // This clobbers the UI regardless of what state it is in b/c we are assuming that no
                        // changes to the VM can happen while this data operation is running.
                        stateMutex.withLockDebug {
                            curRecordId = response.record.id

                            mutUiState.value = response.record.sObject.buildViewingContactUiState(
                                uiSyncState = response.record.localStatus.toUiSyncState(),
                                isEditingEnabled = false,
                                shouldScrollToErrorField = false,
                            )
                        }
                    }

                    is UpsertResponse.Finished -> {
                        stateMutex.withLockDebug {
                            mutUiState.value = uiState.value.copy(dataOperationIsActive = false)
                        }
                        mutDataOperationIsActive.set(false)
                        response.exception?.also { throw it } // WIP crash the app with full exception for now
                    }
                }
            }
        }
    }
}

private sealed interface DetailsDataEvent {
    @JvmInline
    value class Delete(val id: String) : DetailsDataEvent

    @JvmInline
    value class Undelete(val id: String) : DetailsDataEvent

    data class Update(val id: String, val so: ContactObject) : DetailsDataEvent

    @JvmInline
    value class Create(val so: ContactObject) : DetailsDataEvent
}
