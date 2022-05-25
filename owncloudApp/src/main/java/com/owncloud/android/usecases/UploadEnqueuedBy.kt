/**
 * ownCloud Android client application
 *
 * @author Abel García de Prada
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
package com.owncloud.android.usecases

import com.owncloud.android.operations.UploadFileOperation
import com.owncloud.android.workers.UploadFileFromContentUriWorker


/**
 * Select who enqueued the upload. It could be manually by the user or automatically by other workers within the app.
 *
 * Analog to the old CREATED_BY but with fixed options.
 * By default, we consider that it is enqueued manually.
 *
 * Warning -> Order of elements is really important. The ordinal is used to store the value in the database.
 */
enum class UploadEnqueuedBy {
    ENQUEUED_BY_USER,
    ENQUEUED_AS_CAMERA_UPLOAD_PICTURE,
    ENQUEUED_AS_CAMERA_UPLOAD_VIDEO;

    fun fromLegacyCreatedBy(oldCreatedBy: Int): UploadEnqueuedBy {
        return when (oldCreatedBy) {
            UploadFileOperation.CREATED_BY_USER -> ENQUEUED_BY_USER
            UploadFileOperation.CREATED_AS_CAMERA_UPLOAD_PICTURE -> ENQUEUED_AS_CAMERA_UPLOAD_PICTURE
            UploadFileOperation.CREATED_AS_CAMERA_UPLOAD_VIDEO -> ENQUEUED_AS_CAMERA_UPLOAD_VIDEO
            else -> ENQUEUED_BY_USER
        }
    }

    fun toTransferTag(): String {
        return when (this) {
            ENQUEUED_BY_USER -> UploadFileFromContentUriWorker.TRANSFER_TAG_MANUAL_UPLOAD
            ENQUEUED_AS_CAMERA_UPLOAD_PICTURE -> UploadFileFromContentUriWorker.TRANSFER_TAG_CAMERA_UPLOAD
            ENQUEUED_AS_CAMERA_UPLOAD_VIDEO -> UploadFileFromContentUriWorker.TRANSFER_TAG_CAMERA_UPLOAD
        }
    }
}