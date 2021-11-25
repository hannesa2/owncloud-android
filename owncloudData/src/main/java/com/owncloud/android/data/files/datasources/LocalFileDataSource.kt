/**
 * ownCloud Android client application
 *
 * @author Abel García de Prada
 * @author Christian Schabesberger
 * Copyright (C) 2020 ownCloud GmbH.
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

package com.owncloud.android.data.files.datasources

import androidx.lifecycle.LiveData
import com.owncloud.android.domain.files.model.OCFile

interface LocalFileDataSource {
    fun copyFile(sourceFile: OCFile, targetFile: OCFile, finalRemotePath: String, remoteId: String)
    fun getFileById(fileId: Long): OCFile?
    fun getFileByRemotePath(remotePath: String, owner: String): OCFile?
    fun getFolderContent(folderId: Long): List<OCFile>
    fun getFolderContentAsLiveData(folderId: Long): LiveData<List<OCFile>>
    fun getFolderImages(folderId: Long): List<OCFile>
    fun getFilesSharedByLink(owner: String): List<OCFile>
    fun moveFile(sourceFile: OCFile, targetFile: OCFile, finalRemotePath: String, finalStoragePath: String)
    fun saveFilesInFolder(listOfFiles: List<OCFile>, folder: OCFile)
    fun saveFile(file: OCFile)
    fun removeFile(fileId: Long)
    fun renameFile(fileToRename: OCFile, finalRemotePath: String, finalStoragePath: String)
}
