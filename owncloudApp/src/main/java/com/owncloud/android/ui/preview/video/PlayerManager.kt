package com.owncloud.android.ui.preview.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Player.DiscontinuityReason
import com.google.android.exoplayer2.Player.PositionInfo
import com.google.android.exoplayer2.Player.TimelineChangeReason
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.TracksInfo
import com.google.android.exoplayer2.ext.cast.CastPlayer
import com.google.android.exoplayer2.ext.cast.SessionAvailabilityListener
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.ui.StyledPlayerControlView
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.gms.cast.framework.CastContext
import com.owncloud.android.MainApp
import com.owncloud.android.R
import com.owncloud.android.authentication.AccountUtils
import com.owncloud.android.lib.common.OwnCloudAccount
import com.owncloud.android.lib.common.SingleSessionManager
import com.owncloud.android.lib.common.authentication.OwnCloudBasicCredentials
import com.owncloud.android.lib.common.authentication.OwnCloudBearerCredentials
import com.owncloud.android.lib.common.http.HttpConstants
import timber.log.Timber

/**
 * Creates a new manager for [ExoPlayer] and [CastPlayer] and manages players and an internal media queue
 *
 * @param context     A [Context].
 * @param listener    A [Listener] for queue position changes.
 * @param playerView  The [StyledPlayerView] for playback.
 * @param castContext The [CastContext].
 */
class PlayerManager(
    private val context: Context,
    private val listener: Listener,
    private val playerView: StyledPlayerView,
    castContext: CastContext?
) : Player.Listener, SessionAvailabilityListener {

    interface Listener {
        /**
         * Called when the currently played item of the media queue changes.
         */
        fun onQueuePositionChanged(previousIndex: Int, newIndex: Int)

        /**
         * Called when a track of type `trackType` is not supported by the player.
         *
         * @param trackType One of the [C]`.TRACK_TYPE_*` constants.
         */
        fun onUnsupportedTrack(trackType: Int)
    }

    private val localPlayer: Player
    private val castPlayer: CastPlayer
    private val mMediaItems: ArrayList<MediaItem> = ArrayList()
    private var lastSeenTrackGroupInfo: TracksInfo? = null
    private var currentItemIndex: Int
    private var currentPlayer: Player? = null

    var httpDataSourceFactory: HttpDataSource.Factory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)

    private var dataSourceFactory: DataSource.Factory = DataSource.Factory {

        val account = AccountUtils.getCurrentOwnCloudAccount(context)
        val credentials = com.owncloud.android.lib.common.accounts.AccountUtils.getCredentialsForAccount(MainApp.appContext, account)
        val login = credentials.username
        val password = credentials.authToken
        val auth = when (credentials) {
            is OwnCloudBasicCredentials -> { // Basic auth
                val cred = "$login:$password"
                "Basic " + Base64.encodeToString(cred.toByteArray(), Base64.URL_SAFE)
            }
            is OwnCloudBearerCredentials -> { // OAuth
                val bearerToken = credentials.getAuthToken()
                "Bearer $bearerToken"
            }
            else -> ""
        }

        val oca = OwnCloudAccount(account, context)
        oca.loadCredentials(context)

        val dataSource: HttpDataSource = httpDataSourceFactory.createDataSource()
        dataSource.setRequestProperty(HttpConstants.USER_AGENT_HEADER, SingleSessionManager.getUserAgent())
        dataSource.setRequestProperty(HttpConstants.ACCEPT_ENCODING_HEADER, HttpConstants.ACCEPT_ENCODING_IDENTITY)
        dataSource.setRequestProperty(HttpConstants.AUTHORIZATION_HEADER, auth)
        dataSource
    }

    init {
        currentItemIndex = C.INDEX_UNSET
        localPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        localPlayer.addListener(this)
        castPlayer = CastPlayer(castContext!!)
        castPlayer.addListener(this)
        castPlayer.setSessionAvailabilityListener(this)
        setCurrentPlayer(if (castPlayer.isCastSessionAvailable) castPlayer else localPlayer)
    }

    // Queue manipulation methods.
    fun selectLast() {
        setCurrentItem(mMediaItems.size - 1)
    }

    /**
     * Appends `item` to the media queue.
     *
     * @param item The [MediaItem] to append.
     */
    fun addItem(item: MediaItem) {
        mMediaItems.add(item)
        currentPlayer!!.addMediaItem(item)
    }

    /**
     * Returns the item at the given index in the media queue.
     *
     * @param position The index of the item.
     * @return The item at the given index in the media queue.
     */
    fun getItem(position: Int): MediaItem {
        return mMediaItems[position]
    }

    /**
     * Removes the item at the given index from the media queue.
     *
     * @param item The item to remove.
     * @return Whether the removal was successful.
     */
    fun removeItem(item: MediaItem): Boolean {
        val itemIndex = mMediaItems.indexOf(item)
        if (itemIndex == -1) {
            return false
        }
        currentPlayer!!.removeMediaItem(itemIndex)
        mMediaItems.removeAt(itemIndex)
        if (itemIndex == currentItemIndex && itemIndex == mMediaItems.size) {
            maybeSetCurrentItemAndNotify(C.INDEX_UNSET)
        } else if (itemIndex < currentItemIndex) {
            maybeSetCurrentItemAndNotify(currentItemIndex - 1)
        }
        return true
    }

    /**
     * Moves an item within the queue.
     *
     * @param item     The item to move.
     * @param newIndex The target index of the item in the queue.
     * @return Whether the item move was successful.
     */
    fun moveItem(item: MediaItem, newIndex: Int): Boolean {
        val fromIndex = mMediaItems.indexOf(item)
        if (fromIndex == -1) {
            return false
        }

        // Player update.
        currentPlayer!!.moveMediaItem(fromIndex, newIndex)
        mMediaItems.add(newIndex, mMediaItems.removeAt(fromIndex))

        // Index update.
        if (fromIndex == currentItemIndex) {
            maybeSetCurrentItemAndNotify(newIndex)
        } else if (currentItemIndex in (fromIndex + 1)..newIndex) {
            maybeSetCurrentItemAndNotify(currentItemIndex - 1)
        } else if (currentItemIndex in newIndex until fromIndex) {
            maybeSetCurrentItemAndNotify(currentItemIndex + 1)
        }
        return true
    }

    /**
     * Dispatches a given [KeyEvent] to the corresponding view of the current player.
     *
     * @param event The [KeyEvent].
     * @return Whether the event was handled by the target view.
     */
    fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        return playerView.dispatchKeyEvent(event!!)
    }

    /**
     * Releases the manager and the players that it holds.
     */
    fun release() {
        currentItemIndex = C.INDEX_UNSET
        mMediaItems.clear()
        castPlayer.setSessionAvailabilityListener(null)
        castPlayer.release()
        playerView.player = null
        localPlayer.release()
    }

    // Player.Listener implementation.
    override fun onPlaybackStateChanged(playbackState: @Player.State Int) {
        updateCurrentItemIndex()
    }

    override fun onPositionDiscontinuity(
        oldPosition: PositionInfo,
        newPosition: PositionInfo,
        reason: @DiscontinuityReason Int
    ) {
        updateCurrentItemIndex()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: @TimelineChangeReason Int) {
        updateCurrentItemIndex()
    }

    override fun onTracksInfoChanged(tracksInfo: TracksInfo) {
        if (currentPlayer !== localPlayer || tracksInfo === lastSeenTrackGroupInfo) {
            return
        }
        if (!tracksInfo.isTypeSupportedOrEmpty(C.TRACK_TYPE_VIDEO)) {
            listener.onUnsupportedTrack(C.TRACK_TYPE_VIDEO)
        }
        if (!tracksInfo.isTypeSupportedOrEmpty(C.TRACK_TYPE_AUDIO)) {
            listener.onUnsupportedTrack(C.TRACK_TYPE_AUDIO)
        }
        lastSeenTrackGroupInfo = tracksInfo
    }

    override fun onCastSessionAvailable() {
        setCurrentPlayer(castPlayer)
    }

    override fun onCastSessionUnavailable() {
        setCurrentPlayer(localPlayer)
    }

    // Internal methods.
    private fun updateCurrentItemIndex() {
        val playbackState = currentPlayer!!.playbackState
        maybeSetCurrentItemAndNotify(
            if (playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED)
                currentPlayer!!.currentMediaItemIndex
            else
                C.INDEX_UNSET
        )
    }

    private fun setCurrentPlayer(currentPlayer: Player) {
        if (this.currentPlayer === currentPlayer) {
            return
        }
        playerView.player = currentPlayer
        playerView.controllerHideOnTouch = currentPlayer === localPlayer
        if (currentPlayer === castPlayer) {
            playerView.controllerShowTimeoutMs = 0
            playerView.showController()
            playerView.defaultArtwork = ResourcesCompat.getDrawable(context.resources, R.drawable.ic_cast_connected, null)
        } else { // currentPlayer == localPlayer
            playerView.controllerShowTimeoutMs = StyledPlayerControlView.DEFAULT_SHOW_TIMEOUT_MS
            playerView.defaultArtwork = null
        }

        // Player state management.
        var playbackPositionMs = C.TIME_UNSET
        var currentItemIndex = C.INDEX_UNSET
        var playWhenReady = false
        val previousPlayer = this.currentPlayer
        if (previousPlayer != null) {
            // Save state from the previous player.
            val playbackState = previousPlayer.playbackState
            if (playbackState != Player.STATE_ENDED) {
                playbackPositionMs = previousPlayer.currentPosition
                playWhenReady = previousPlayer.playWhenReady
                currentItemIndex = previousPlayer.currentMediaItemIndex
                if (currentItemIndex != this.currentItemIndex) {
                    playbackPositionMs = C.TIME_UNSET
                    currentItemIndex = this.currentItemIndex
                }
            }
            previousPlayer.stop()
            previousPlayer.clearMediaItems()
        }
        this.currentPlayer = currentPlayer

        // Media queue management.
        currentPlayer.setMediaItems(mMediaItems, currentItemIndex, playbackPositionMs)
        currentPlayer.playWhenReady = playWhenReady
        currentPlayer.prepare()
    }

    /**
     * Starts playback of the item at the given index.
     *
     * @param itemIndex The index of the item to play.
     */
    private fun setCurrentItem(itemIndex: Int) {
        maybeSetCurrentItemAndNotify(itemIndex)
        if (currentPlayer!!.currentTimeline.windowCount != mMediaItems.size) {
            // This only happens with the cast player. The receiver app in the cast device clears the
            // timeline when the last item of the timeline has been played to end.
            currentPlayer!!.setMediaItems(mMediaItems, itemIndex, C.TIME_UNSET)
        } else {
            currentPlayer!!.seekTo(itemIndex, C.TIME_UNSET)
        }
        currentPlayer!!.playWhenReady = true
        Handler(Looper.getMainLooper()).postDelayed({
            currentPlayer!!.playerError?.let {
                Toast.makeText(context, "${it.message} ${it.errorCode} ${it.errorCodeName}", Toast.LENGTH_LONG).show()
                Timber.e("${it.message} ${it.errorCode} ${it.errorCodeName}")
            }
        }, 2000)
    }

    private fun maybeSetCurrentItemAndNotify(currentItemIndex: Int) {
        if (this.currentItemIndex != currentItemIndex) {
            val oldIndex = this.currentItemIndex
            this.currentItemIndex = currentItemIndex
            listener.onQueuePositionChanged(oldIndex, currentItemIndex)
        }
    }

}
