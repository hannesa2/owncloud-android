/**
 * ownCloud Android client application
 *
 * @author Jorge Aguado Recio
 *
 * Copyright (C) 2026 ownCloud GmbH.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.owncloud.android.domain.links.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class OCLink(
    val id: String,
    val createdDateTime: String,
    val expirationDateTime: String?,
    val hasPassword: Boolean,
    val displayName: String,
    val type: OCLinkType,
    val webUrl: String
) : Parcelable

enum class OCLinkType {
    CAN_VIEW, CAN_EDIT, CAN_UPLOAD, CREATE_ONLY, INTERNAL;

    companion object {
        private const val CAN_VIEW_TYPE_STRING = "view"
        private const val CAN_EDIT_TYPE_STRING = "edit"
        private const val CAN_UPLOAD_TYPE_STRING = "upload"
        private const val CREATE_ONLY_TYPE_STRING = "createOnly"
        private const val INTERNAL_TYPE_STRING = "internal"

        fun parseFromString(type: String): OCLinkType =
            when (type) {
                CAN_VIEW_TYPE_STRING -> CAN_VIEW
                CAN_EDIT_TYPE_STRING -> CAN_EDIT
                CAN_UPLOAD_TYPE_STRING -> CAN_UPLOAD
                CREATE_ONLY_TYPE_STRING -> CREATE_ONLY
                else -> INTERNAL
            }

        fun toString(type: OCLinkType): String =
            when (type) {
                CAN_VIEW -> CAN_VIEW_TYPE_STRING
                CAN_EDIT -> CAN_EDIT_TYPE_STRING
                CAN_UPLOAD -> CAN_UPLOAD_TYPE_STRING
                CREATE_ONLY -> CREATE_ONLY_TYPE_STRING
                INTERNAL -> INTERNAL_TYPE_STRING
            }
    }
}
