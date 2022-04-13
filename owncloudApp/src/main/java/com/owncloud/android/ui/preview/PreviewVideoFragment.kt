/*
 * ownCloud Android client application
 *
 * @author David A. Velasco
 * @author David González Verdugo
 * @author Christian Schabesberger
 * @author Shashvat Kedia
 * Copyright (C) 2021 ownCloud GmbH.
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.owncloud.android.ui.preview

import android.accounts.Account
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentResolver
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.util.MimeTypes
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.owncloud.android.R
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.files.FileMenuFilter
import com.owncloud.android.ui.activity.FileActivity
import com.owncloud.android.ui.activity.FileDisplayActivity
import com.owncloud.android.ui.controller.TransferProgressController
import com.owncloud.android.ui.dialog.ConfirmationDialogFragment
import com.owncloud.android.ui.dialog.RemoveFilesDialogFragment
import com.owncloud.android.ui.fragment.FileFragment
import com.owncloud.android.ui.preview.PrepareVideoPlayerAsyncTask.OnPrepareVideoPlayerTaskListener
import com.owncloud.android.ui.preview.video.PlayerManager
import timber.log.Timber
import java.util.Locale

/**
 * This fragment shows a preview of a downloaded video file, or starts streaming if file is not downloaded yet.
 *
 * Trying to get an instance with NULL [OCFile] or ownCloud [Account] values will produce an [IllegalStateException].
 *
 * Creates an empty fragment to preview video files.
 *
 * MUST BE KEPT: the system uses it when tries to reinstantiate a fragment automatically (for instance, when the device is turned a aside).
 *
 * DO NOT CALL IT: an [OCFile] and [Account] must be provided for a successful construction
 */
class PreviewVideoFragment : FileFragment(), View.OnClickListener, Player.EventListener, OnPrepareVideoPlayerTaskListener, PlayerManager.Listener {

    private lateinit var syncProgressBar: ProgressBar
    private var mAccount: Account? = null
    private var mProgressController: TransferProgressController? = null
    private var playerView: StyledPlayerView? = null
    private var player: SimpleExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var fullscreenButton: ImageButton? = null
    private var mExoPlayerBooted = false
    private var mAutoplay = true
    private var mPlaybackPosition: Long = 0
    private var castContext: CastContext? = null
    private var playerManager: PlayerManager? = null

    @SuppressLint("InflateParams")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        super.onCreateView(inflater, container, savedInstanceState)
        val view = inflater.inflate(R.layout.video_preview, null)
        setHasOptionsMenu(true)
        syncProgressBar = view.findViewById(R.id.syncProgressBar)
        syncProgressBar.visibility = View.GONE
        playerView = view.findViewById(R.id.player_view)
        fullscreenButton = view.findViewById(R.id.fullscreen_button)
        fullscreenButton?.setOnClickListener(this)

        //        playerView.getPlayer().addListener(new Player.Listener() {
        //            @Override
        //            public void onPlaybackStateChanged(@Player.State int state) {
        //                // If player is already, show full screen button
        //                if (state == ExoPlayer.STATE_READY) {
        //                    fullScreenButton.setVisibility(View.VISIBLE);
        //                    if (player != null && !mExoPlayerBooted) {
        //                        mExoPlayerBooted = true;
        //                        player.seekTo(mPlaybackPosition);
        //                        player.setPlayWhenReady(mAutoplay);
        //                    }
        //
        //                } else if (state == ExoPlayer.STATE_ENDED) {
        //                    fullScreenButton.setVisibility(View.GONE);
        //                }
        //            }
        //        });
        return view
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        // Getting the cast context later than onStart can cause device discovery not to take place.
        try {
            castContext = CastContext.getSharedInstance(requireContext())
        } catch (e: RuntimeException) {
            Toast.makeText(requireContext(), "Error during cast init " + e.javaClass.simpleName, Toast.LENGTH_LONG)
                .show()
            Timber.e(e)
        }
        val file: OCFile?
        if (savedInstanceState == null) {
            val args = requireArguments()
            file = args.getParcelable(EXTRA_FILE)
            setFile(file)
            mAccount = args.getParcelable(EXTRA_ACCOUNT)
            mAutoplay = args.getBoolean(EXTRA_AUTOPLAY)
            mPlaybackPosition = args.getLong(EXTRA_PLAY_POSITION)
        } else {
            file = savedInstanceState.getParcelable(EXTRA_FILE)
            setFile(file)
            mAccount = savedInstanceState.getParcelable(EXTRA_ACCOUNT)
            mAutoplay = savedInstanceState.getBoolean(EXTRA_AUTOPLAY)
            mPlaybackPosition = savedInstanceState.getLong(EXTRA_PLAY_POSITION)
        }
        checkNotNull(file) { "Instanced with a NULL OCFile" }
        checkNotNull(mAccount) { "Instanced with a NULL ownCloud Account" }
        check(file.isVideo) { "Not a video file" }
        mProgressController = TransferProgressController(mContainerActivity).apply {
            setProgressBar(syncProgressBar)
        }
    }

    override fun onStart() {
        super.onStart()
        Timber.v("onStart")
        val file = file
        if (castContext == null) {
            // There is no Cast context to work with. Do nothing.
            return
        }
        playerManager = PlayerManager(requireContext(), this, playerView!!, castContext)
        //        mediaQueueList.setAdapter(mediaQueueListAdapter);
        if (file != null) {
            mProgressController!!.startListeningProgressFor(file, mAccount)
            // Prepare video player asynchronously
            PrepareVideoPlayerAsyncTask(requireActivity(), this, getFile(), mAccount!!).execute()
        }
    }

    override fun onResume() {
        super.onResume()
        Timber.v("onResume")
        //        preparePlayer();
    }

    override fun onPause() {
        super.onPause()
        //        releasePlayer();
        if (castContext == null) {
            return
        }
        //        mediaQueueListAdapter.notifyItemRangeRemoved(0, mediaQueueListAdapter.getItemCount());
        //        mediaQueueList.setAdapter(null);
        playerManager!!.release()
        playerManager = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Timber.v("onSaveInstanceState")
        outState.putParcelable(EXTRA_FILE, file)
        outState.putParcelable(EXTRA_ACCOUNT, mAccount)
        if (player != null) {
            outState.putBoolean(EXTRA_AUTOPLAY, mAutoplay)
            outState.putLong(EXTRA_PLAY_POSITION, player!!.currentPosition)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Timber.v("onActivityResult %s", this)
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            mAutoplay = data!!.extras!!.getBoolean(PreviewVideoActivity.EXTRA_AUTOPLAY)
            mPlaybackPosition = data.extras!!.getLong(PreviewVideoActivity.EXTRA_START_POSITION)
            mExoPlayerBooted = false
        }
    }

    // OnClickListener methods
    override fun onClick(view: View) {
        if (view === fullscreenButton) {
            releasePlayer()
            startFullScreenVideo()
        }
    }

    private fun startFullScreenVideo() {
        val i = Intent(activity, PreviewVideoActivity::class.java)
        i.putExtra(EXTRA_AUTOPLAY, playerView!!.player!!.playWhenReady)
        i.putExtra(EXTRA_PLAY_POSITION, playerView!!.player!!.currentPosition)
        i.putExtra(FileActivity.EXTRA_FILE, file)
        startActivityForResult(i, FileActivity.REQUEST_CODE__LAST_SHARED + 1)
    }

    // Progress bar
    override fun onTransferServiceConnected() {
        if (mProgressController != null) {
            mProgressController!!.startListeningProgressFor(file, mAccount)
        }
    }

    override fun updateViewForSyncInProgress() {
        mProgressController!!.showProgressBar()
    }

    override fun updateViewForSyncOff() {
        mProgressController!!.hideProgressBar()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.video_actions_menu, menu)
        CastButtonFactory.setUpMediaRouteButton(requireContext(), menu, R.id.action_cast)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        val mf = FileMenuFilter(
            file,
            mAccount,
            mContainerActivity,
            activity
        )
        mf.filter(menu, false, false, false, false)

        // additional restrictions for this fragment
        val item = menu.findItem(R.id.action_search)
        if (item != null) {
            item.isVisible = false
            item.isEnabled = false
        }
    }

    @SuppressLint("NonConstantResourceId")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_share_file -> {
                releasePlayer()
                mContainerActivity.fileOperationsHelper.showShareFile(file)
                true
            }
            R.id.action_open_file_with -> {
                openFile()
                true
            }
            R.id.action_remove_file -> {
                val dialog = RemoveFilesDialogFragment.newInstance(file)
                dialog.show(parentFragmentManager, ConfirmationDialogFragment.FTAG_CONFIRMATION)
                true
            }
            R.id.action_see_details -> {
                seeDetails()
                true
            }
            R.id.action_send_file -> {
                releasePlayer()
                mContainerActivity.fileOperationsHelper.sendDownloadedFile(file)
                true
            }
            R.id.action_sync_file -> {
                mContainerActivity.fileOperationsHelper.syncFile(file)
                true
            }
            R.id.action_set_available_offline -> {
                mContainerActivity.fileOperationsHelper.toggleAvailableOffline(file, true)
                true
            }
            R.id.action_unset_available_offline -> {
                mContainerActivity.fileOperationsHelper.toggleAvailableOffline(file, false)
                true
            }
            R.id.action_download_file -> {
                releasePlayer()
                // Show progress bar
                syncProgressBar.visibility = View.VISIBLE
                mContainerActivity.fileOperationsHelper.syncFile(file)
                true
            }
            R.id.action_cancel_sync -> {
                (mContainerActivity as FileDisplayActivity).cancelTransference(file)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // Video player internal methods
    private fun preparePlayer() {
        val videoTrackSelectionFactory = AdaptiveTrackSelection.Factory()
        trackSelector = DefaultTrackSelector(videoTrackSelectionFactory)

        // Video streaming is only supported at Jelly Bean or higher Android versions (>= API 16)

        // Create the player
        player = SimpleExoPlayer.Builder(requireContext()).setTrackSelector(trackSelector!!)
            .setLoadControl(DefaultLoadControl()).build()
        player!!.addListener(this)

        // Bind the player to the view.
        playerView!!.player = player

        // Prepare video player asynchronously
        PrepareVideoPlayerAsyncTask(requireActivity(), this, file, mAccount!!).execute()
    }

    fun getMimeType(uri: Uri): String? {
        val mimeType: String? = if (ContentResolver.SCHEME_CONTENT == uri.scheme) {
            val cr = requireContext().contentResolver
            cr.getType(uri)
        } else {
            val fileExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension.lowercase(Locale.getDefault()))
        }
        return mimeType
    }

    /**
     * Called after preparing the player asynchronously
     *
     * @param mediaItem media to be played
     */
    override fun OnPrepareVideoPlayerTaskCallback(mediaItem: MediaItem?) {
        Timber.v("playerPrepared")
        val mediaItemX =
            MediaItem.Builder()
//                .setUri("https://www.mxtracks.info/owncloud/remote.php/dav/files/hannes/Filme/Doku/Alpha%20Centauri-071-Was%20ist%20Gleichzeitigkeit.mp4")
//                .setUri("https://storage.googleapis.com/wvmedia/clear/h264/tears/tears.mpd")
                .setMimeType(MimeTypes.VIDEO_MP4)
                .setUri(mediaItem!!.localConfiguration!!.uri)
                .build()
        Timber.d("mimeType:" + mediaItemX.localConfiguration!!.mimeType + " uri:" + mediaItemX.localConfiguration!!.uri)
        playerManager!!.addItem(mediaItemX)
        playerManager!!.selectLast()
        //        player.prepare(mediaSource);
    }

    fun releasePlayer() {
        if (player != null) {
            mAutoplay = player!!.playWhenReady
            updateResumePosition()
            player!!.release()
            trackSelector = null
            Timber.v("playerReleased")
        }
    }

    private fun updateResumePosition() {
        mPlaybackPosition = player!!.currentPosition
    }

    // Video player eventListener implementation
    override fun onPlayerError(error: PlaybackException) {
        Timber.e(error, "Error in video player")
        showAlertDialog(PreviewVideoErrorAdapter.handlePreviewVideoError(error as ExoPlaybackException, context))
    }

    /**
     * Show an alert dialog with the error produced while playing the video and initialize a
     * specific behaviour when necessary
     *
     * @param previewVideoError player error with the needed info
     */
    private fun showAlertDialog(previewVideoError: PreviewVideoError) {
        AlertDialog.Builder(requireActivity())
            .setMessage(previewVideoError.errorMessage)
            .setPositiveButton(android.R.string.VideoView_error_button) { _: DialogInterface?, _: Int ->
                if (previewVideoError.isFileSyncNeeded && mContainerActivity != null) {
                    // Initialize the file download
                    mContainerActivity.fileOperationsHelper.syncFile(file)
                }

                // This solution is not the best one but is an easy way to handle
                // expiration error from here, without modifying so much code or involving other parts
                if (previewVideoError.isParentFolderSyncNeeded) {
                    // Start to sync the parent file folder
                    val folder = OCFile(file.parentRemotePath)
                    (requireActivity() as FileDisplayActivity).startSyncFolderOperation(folder, false)
                }
            }
            .setCancelable(false)
            .show()
    }

    //    @Override
    //    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
    //        // If player is already, show full screen button
    //        if (playbackState == ExoPlayer.STATE_READY) {
    //            fullScreenButton.setVisibility(View.VISIBLE);
    //            if (player != null && !mExoPlayerBooted) {
    //                mExoPlayerBooted = true;
    //                player.seekTo(mPlaybackPosition);
    //                player.setPlayWhenReady(mAutoplay);
    //            }
    //
    //        } else if (playbackState == ExoPlayer.STATE_ENDED) {
    //            fullScreenButton.setVisibility(View.GONE);
    //        }
    //    }
    // File extra methods
    override fun onFileMetadataChanged(updatedFile: OCFile) {
        file = updatedFile
        requireActivity().invalidateOptionsMenu()
    }

    override fun onFileMetadataChanged() {
        val storageManager = mContainerActivity.storageManager
        if (storageManager != null) {
            file = storageManager.getFileByPath(file.remotePath)
        }
        requireActivity().invalidateOptionsMenu()
    }

    override fun onFileContentChanged() {
        // Reset the player with the updated file
        releasePlayer()
        //        preparePlayer();
        if (player != null) {
            mPlaybackPosition = 0
            player!!.playWhenReady = mAutoplay
        }
    }

    private fun seeDetails() {
        releasePlayer()
        mContainerActivity.showDetails(file)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Timber.v("onConfigurationChanged %s", this)
    }

    /**
     * Opens the previewed file with an external application.
     */
    private fun openFile() {
        releasePlayer()
        mContainerActivity.fileOperationsHelper.openFile(file)
        finish()
    }

    private fun finish() {
        requireActivity().onBackPressed()
    }

    override fun onQueuePositionChanged(previousIndex: Int, newIndex: Int) {
        //        if (previousIndex != C.INDEX_UNSET) {
        //            mediaQueueListAdapter.notifyItemChanged(previousIndex);
        //        }
        //        if (newIndex != C.INDEX_UNSET) {
        //            mediaQueueListAdapter.notifyItemChanged(newIndex);
        //        }
    }

    override fun onUnsupportedTrack(trackType: Int) {
        if (trackType == C.TRACK_TYPE_AUDIO) {
            Timber.e("\$trackType %s", getString(R.string.error_unsupported_audio))
            Toast.makeText(requireContext(), R.string.error_unsupported_audio, Toast.LENGTH_LONG).show()
        } else if (trackType == C.TRACK_TYPE_VIDEO) {
            Timber.e("\$trackType %s", getString(R.string.error_unsupported_video))
            Toast.makeText(requireContext(), R.string.error_unsupported_video, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_FILE = "FILE"
        const val EXTRA_ACCOUNT = "ACCOUNT"
        const val MIME_TYPE_DASH = MimeTypes.APPLICATION_MPD
        const val MIME_TYPE_HLS = MimeTypes.APPLICATION_M3U8
        const val MIME_TYPE_SS = MimeTypes.APPLICATION_SS
        const val MIME_TYPE_VIDEO_MP4 = MimeTypes.VIDEO_MP4

        /**
         * Key to receive a flag signaling if the video should be started immediately
         */
        private const val EXTRA_AUTOPLAY = "AUTOPLAY"

        /**
         * Key to receive the position of the playback where the video should be put at start
         */
        private const val EXTRA_PLAY_POSITION = "START_POSITION"

        /**
         * Public factory method to create new PreviewVideoFragment instances.
         *
         * @param file                  An [OCFile] to preview in the fragment
         * @param account               ownCloud account containing file
         * @param startPlaybackPosition Time in milliseconds where the play should be started
         * @param autoplay              If 'true', the file will be played automatically when
         * the fragment is displayed.
         * @return Fragment ready to be used.
         */
        fun newInstance(
            file: OCFile?,
            account: Account?,
            startPlaybackPosition: Int,
            autoplay: Boolean
        ): PreviewVideoFragment {
            val frag = PreviewVideoFragment()
            val args = Bundle()
            args.putParcelable(EXTRA_FILE, file)
            args.putParcelable(EXTRA_ACCOUNT, account)
            args.putLong(EXTRA_PLAY_POSITION, startPlaybackPosition.toLong())
            args.putBoolean(EXTRA_AUTOPLAY, autoplay)
            frag.arguments = args
            return frag
        }

        /**
         * Helper method to test if an [OCFile] can be passed to a [PreviewVideoFragment]
         * to be previewed.
         *
         * @param file File to test if can be previewed.
         * @return 'True' if the file can be handled by the fragment.
         */
        @JvmStatic
        fun canBePreviewed(file: OCFile?): Boolean {
            return file != null && file.isVideo
        }
    }
}