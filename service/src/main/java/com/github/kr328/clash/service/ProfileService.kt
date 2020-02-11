package com.github.kr328.clash.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.utils.Log
import com.github.kr328.clash.service.data.ClashDatabase
import com.github.kr328.clash.service.data.ClashProfileDao
import com.github.kr328.clash.service.data.ClashProfileEntity
import com.github.kr328.clash.service.transact.ProfileRequest
import com.github.kr328.clash.service.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.FileNotFoundException
import java.util.*

class ProfileService : BaseService() {
    private val service = this
    private val queue: MutableMap<Long, Channel<ProfileRequest>> = Hashtable()

    private val profiles: ClashProfileDao by lazy {
        ClashDatabase.getInstance(service).openClashProfileDao()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return object : IProfileService.Stub() {
            override fun enqueueRequest(request: ProfileRequest?) {
                service.enqueueRequest(request ?: return)
            }

            override fun queryActiveProfile(): ClashProfileEntity? {
                return profiles.queryActiveProfile()
            }

            override fun queryProfiles(): Array<ClashProfileEntity> {
                return profiles.queryProfiles()
            }
        }
    }

    private fun createChannelForRequests(id: Long): Channel<ProfileRequest> {
        return Channel<ProfileRequest>(Channel.UNLIMITED).also {
            launch {
                try {
                    while (isActive) {
                        Log.d("Coroutine for $id launched")

                        val request = withTimeout(1000 * 30) {
                            it.receive()
                        }

                        Log.d("Handling $id")
                        handleRequest(request)
                    }
                }
                finally {
                    Log.d("Coroutine for $id exited")

                    queue.remove(id)
                }
            }
        }
    }

    private fun enqueueRequest(request: ProfileRequest) {
        launch {
            queue.computeIfAbsent(request.id) {
                createChannelForRequests(it)
            }.send(request)
        }
    }

    private suspend fun handleRequest(request: ProfileRequest) {
        try {
            request.callback?.send(null)

            when (request.action) {
                ProfileRequest.Action.UPDATE_OR_CREATE ->
                    handleUpdateOrCreate(request)
                ProfileRequest.Action.REMOVE ->
                    removeProfile(request)
            }

            request.callback?.complete()

            broadcastProfileChanged(this)
        } catch (e: Exception) {
            request.callback?.completeExceptionally(e.message)
        }
    }

    private suspend fun handleUpdateOrCreate(request: ProfileRequest) =
        withContext(Dispatchers.IO) {
            val id = request.id

            val entity: ClashProfileEntity =
                if (id == 0L) {
                    ClashProfileEntity(
                        requireNotNull(request.name),
                        requireNotNull(request.type),
                        requireNotNull(request.url).toString(),
                        request.source?.toString(),
                        RandomUtils.fileName(profileDir, ".yaml"),
                        RandomUtils.fileName(clashDir),
                        false,
                        0,
                        request.interval.takeIf { it >= 0 } ?: 0
                    )
                } else {
                    val e = profiles.queryProfileById(id) ?: return@withContext

                    e.copy(
                        name = request.name ?: e.name,
                        uri = request.url?.toString() ?: e.uri,
                        updateInterval = request.interval.takeIf { it >= 0 } ?: e.updateInterval
                    )
                }

            val url = Uri.parse(entity.uri)

            if (url == null || url == Uri.EMPTY)
                throw IllegalArgumentException("Invalid url $url")

            Log.d("Profile ${entity.name} downloading")

            downloadProfile(url, profileDir.resolve(entity.file), clashDir.resolve(entity.base))

            val newEntity = entity.copy(lastUpdate = System.currentTimeMillis())

            val newId = if (entity.id == 0L)
                profiles.getId(profiles.addProfile(newEntity))
            else
                profiles.updateProfile(newEntity).run { entity.id }

            if (entity.updateInterval > 0) {
                val nextRequest =
                    ProfileRequest().action(ProfileRequest.Action.UPDATE_OR_CREATE).withId(newId)

                requireNotNull(getSystemService(AlarmManager::class.java)).set(
                    AlarmManager.RTC,
                    entity.lastUpdate + entity.updateInterval,
                    PendingIntent.getBroadcast(
                        service,
                        RandomUtils.nextInt(),
                        Intent(Intents.INTENT_ACTION_PROFILE_ENQUEUE_REQUEST)
                            .setComponent(ProfileRequestReceiver::class.componentName)
                            .putExtra(Intents.INTENT_EXTRA_PROFILE_REQUEST, nextRequest),
                        PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            }
        }

    private suspend fun removeProfile(request: ProfileRequest) = withContext(Dispatchers.IO) {
        val entity = profiles.queryProfileById(request.id) ?: return@withContext

        clashDir.resolve(entity.base).deleteRecursively()
        profileDir.resolve(entity.file).delete()

        profiles.removeProfile(entity.id)
    }

    private suspend fun downloadProfile(source: Uri, target: File, baseDir: File) {
        try {
            target.parentFile?.mkdirs()
            baseDir.mkdirs()

            if (source.scheme == "content" || source.scheme == "file") {
                val fd = contentResolver.openFileDescriptor(source, "r")
                    ?: throw FileNotFoundException("Unable to open file $source")

                Clash.downloadProfile(fd.fd, target, baseDir).await()
            } else {
                Clash.downloadProfile(source.toString(), target, baseDir).await()
            }
        } catch (e: Exception) {
            target.delete()
            baseDir.deleteRecursively()

            throw e
        }
    }
}