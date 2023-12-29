package com.x8bit.bitwarden.data.vault.datasource.disk

import com.x8bit.bitwarden.data.platform.repository.util.bufferedMutableSharedFlow
import com.x8bit.bitwarden.data.vault.datasource.disk.dao.CiphersDao
import com.x8bit.bitwarden.data.vault.datasource.disk.dao.CollectionsDao
import com.x8bit.bitwarden.data.vault.datasource.disk.dao.FoldersDao
import com.x8bit.bitwarden.data.vault.datasource.disk.entity.CipherEntity
import com.x8bit.bitwarden.data.vault.datasource.disk.entity.CollectionEntity
import com.x8bit.bitwarden.data.vault.datasource.disk.entity.FolderEntity
import com.x8bit.bitwarden.data.vault.datasource.network.model.SyncResponseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Default implementation of [VaultDiskSource].
 */
class VaultDiskSourceImpl(
    private val ciphersDao: CiphersDao,
    private val collectionsDao: CollectionsDao,
    private val foldersDao: FoldersDao,
    private val json: Json,
) : VaultDiskSource {

    private val forceCiphersFlow = bufferedMutableSharedFlow<List<SyncResponseJson.Cipher>>()
    private val forceCollectionsFlow =
        bufferedMutableSharedFlow<List<SyncResponseJson.Collection>>()
    private val forceFolderFlow = bufferedMutableSharedFlow<List<SyncResponseJson.Folder>>()

    override fun getCiphers(
        userId: String,
    ): Flow<List<SyncResponseJson.Cipher>> =
        merge(
            forceCiphersFlow,
            ciphersDao
                .getAllCiphers(userId = userId)
                .map { entities ->
                    entities.map { entity ->
                        json.decodeFromString<SyncResponseJson.Cipher>(entity.cipherJson)
                    }
                },
        )

    override fun getCollections(
        userId: String,
    ): Flow<List<SyncResponseJson.Collection>> =
        merge(
            forceCollectionsFlow,
            collectionsDao
                .getAllCollections(userId = userId)
                .map { entities ->
                    entities.map { entity ->
                        SyncResponseJson.Collection(
                            id = entity.id,
                            name = entity.name,
                            organizationId = entity.organizationId,
                            shouldHidePasswords = entity.shouldHidePasswords,
                            externalId = entity.externalId,
                            isReadOnly = entity.isReadOnly,
                        )
                    }
                },
        )

    override fun getFolders(
        userId: String,
    ): Flow<List<SyncResponseJson.Folder>> =
        merge(
            forceFolderFlow,
            foldersDao
                .getAllFolders(userId = userId)
                .map { entities ->
                    entities.map { entity ->
                        SyncResponseJson.Folder(
                            id = entity.id,
                            name = entity.name,
                            revisionDate = entity.revisionDate,
                        )
                    }
                },
        )

    override suspend fun replaceVaultData(
        userId: String,
        vault: SyncResponseJson,
    ) {
        coroutineScope {
            val deferredCiphers = async {
                ciphersDao.replaceAllCiphers(
                    userId = userId,
                    ciphers = vault.ciphers.orEmpty().map { cipher ->
                        CipherEntity(
                            id = cipher.id,
                            userId = userId,
                            cipherType = json.encodeToString(cipher.type),
                            cipherJson = json.encodeToString(cipher),
                        )
                    },
                )
            }
            val deferredCollections = async {
                collectionsDao.replaceAllCollections(
                    userId = userId,
                    collections = vault.collections.orEmpty().map { collection ->
                        CollectionEntity(
                            userId = userId,
                            id = collection.id,
                            name = collection.name,
                            organizationId = collection.organizationId,
                            shouldHidePasswords = collection.shouldHidePasswords,
                            externalId = collection.externalId,
                            isReadOnly = collection.isReadOnly,
                        )
                    },
                )
            }
            val deferredFolders = async {
                foldersDao.replaceAllFolders(
                    userId = userId,
                    folders = vault.folders.orEmpty().map { folder ->
                        FolderEntity(
                            userId = userId,
                            id = folder.id,
                            name = folder.name,
                            revisionDate = folder.revisionDate,
                        )
                    },
                )
            }
            // When going from 0 items to 0 items, the respective dao flow will not re-emit
            // So we use this to give it a little push.
            if (!deferredCiphers.await()) {
                forceCiphersFlow.tryEmit(emptyList())
            }
            if (!deferredCollections.await()) {
                forceCollectionsFlow.tryEmit(emptyList())
            }
            if (!deferredFolders.await()) {
                forceFolderFlow.tryEmit(emptyList())
            }
        }
    }

    override suspend fun deleteVaultData(userId: String) {
        coroutineScope {
            val deferredCiphers = async { ciphersDao.deleteAllCiphers(userId = userId) }
            val deferredCollections = async { collectionsDao.deleteAllCollections(userId = userId) }
            val deferredFolders = async { foldersDao.deleteAllFolders(userId = userId) }
            awaitAll(
                deferredCiphers,
                deferredCollections,
                deferredFolders,
            )
        }
    }
}