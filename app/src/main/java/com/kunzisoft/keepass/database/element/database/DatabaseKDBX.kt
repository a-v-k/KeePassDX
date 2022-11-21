/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *     
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.database.element.database

import android.content.res.Resources
import android.util.Base64
import android.util.Log
import com.kunzisoft.encrypt.HashManager
import org.digicraft.keepass.R
import com.kunzisoft.keepass.database.action.node.NodeHandler
import com.kunzisoft.keepass.database.crypto.EncryptionAlgorithm
import com.kunzisoft.keepass.database.crypto.VariantDictionary
import com.kunzisoft.keepass.database.crypto.kdf.AesKdf
import com.kunzisoft.keepass.database.crypto.kdf.KdfEngine
import com.kunzisoft.keepass.database.crypto.kdf.KdfFactory
import com.kunzisoft.keepass.database.crypto.kdf.KdfParameters
import com.kunzisoft.keepass.database.element.CustomData
import com.kunzisoft.keepass.database.element.DateInstant
import com.kunzisoft.keepass.database.element.DeletedObject
import com.kunzisoft.keepass.database.element.Tags
import com.kunzisoft.keepass.database.element.binary.BinaryData
import com.kunzisoft.keepass.database.element.database.DatabaseKDB.Companion.BACKUP_FOLDER_TITLE
import com.kunzisoft.keepass.database.element.entry.EntryKDBX
import com.kunzisoft.keepass.database.element.entry.FieldReferencesEngine
import com.kunzisoft.keepass.database.element.group.GroupKDBX
import com.kunzisoft.keepass.database.element.icon.IconImageCustom
import com.kunzisoft.keepass.database.element.icon.IconImageStandard
import com.kunzisoft.keepass.database.element.node.NodeId
import com.kunzisoft.keepass.database.element.node.NodeIdUUID
import com.kunzisoft.keepass.database.element.node.NodeKDBXInterface
import com.kunzisoft.keepass.database.element.node.NodeVersioned
import com.kunzisoft.keepass.database.element.security.MemoryProtectionConfig
import com.kunzisoft.keepass.database.element.template.Template
import com.kunzisoft.keepass.database.element.template.TemplateEngineCompatible
import com.kunzisoft.keepass.database.file.DatabaseHeaderKDBX.Companion.FILE_VERSION_31
import com.kunzisoft.keepass.database.file.DatabaseHeaderKDBX.Companion.FILE_VERSION_40
import com.kunzisoft.keepass.database.file.DatabaseHeaderKDBX.Companion.FILE_VERSION_41
import com.kunzisoft.keepass.utils.StringUtil.removeSpaceChars
import com.kunzisoft.keepass.utils.StringUtil.toHexString
import com.kunzisoft.keepass.utils.UnsignedInt
import com.kunzisoft.keepass.utils.longTo8Bytes
import org.apache.commons.codec.binary.Hex
import org.w3c.dom.Node
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*
import javax.crypto.Mac
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import kotlin.collections.HashSet
import kotlin.math.min


class DatabaseKDBX : DatabaseVersioned<UUID, UUID, GroupKDBX, EntryKDBX> {

    var hmacKey: ByteArray? = null
        private set

    override var encryptionAlgorithm: EncryptionAlgorithm = EncryptionAlgorithm.AESRijndael

    fun setEncryptionAlgorithmFromUUID(uuid: UUID) {
        encryptionAlgorithm = EncryptionAlgorithm.getFrom(uuid)
    }

    override val availableEncryptionAlgorithms: List<EncryptionAlgorithm> = listOf(
        EncryptionAlgorithm.AESRijndael,
        EncryptionAlgorithm.Twofish,
        EncryptionAlgorithm.ChaCha20
    )

    var kdfParameters: KdfParameters? = null

    override var kdfEngine: KdfEngine?
        get() = getKdfEngineFromParameters(kdfParameters)
        set(value) {
            value?.let {
                if (kdfParameters?.uuid != value.defaultParameters.uuid)
                    kdfParameters = value.defaultParameters
                numberKeyEncryptionRounds = value.defaultKeyRounds
                memoryUsage = value.defaultMemoryUsage
                parallelism = value.defaultParallelism
            }
        }

    private fun getKdfEngineFromParameters(kdfParameters: KdfParameters?): KdfEngine? {
        if (kdfParameters == null) {
            return null
        }
        for (engine in kdfAvailableList) {
            if (engine.uuid == kdfParameters.uuid) {
                return engine
            }
        }
        return null
    }

    fun randomizeKdfParameters() {
        kdfParameters?.let {
            kdfEngine?.randomize(it)
        }
    }

    override val kdfAvailableList: List<KdfEngine> = listOf(
        KdfFactory.aesKdf,
        KdfFactory.argon2dKdf,
        KdfFactory.argon2idKdf
    )

    var compressionAlgorithm = CompressionAlgorithm.GZip

    private val mFieldReferenceEngine = FieldReferencesEngine(this)
    private val mTemplateEngine = TemplateEngineCompatible(this)

    var kdbxVersion = UnsignedInt(0)
    var name = ""
    var nameChanged = DateInstant()
    var description = ""
    var descriptionChanged = DateInstant()
    var defaultUserName = ""
    var defaultUserNameChanged = DateInstant()
    var settingsChanged = DateInstant()
    var keyLastChanged = DateInstant()
    var keyChangeRecDays: Long = -1
    var keyChangeForceDays: Long = 1
    var isKeyChangeForceOnce = false

    var maintenanceHistoryDays = UnsignedInt(365)
    var color = ""
    /**
     * Determine if RecycleBin is enable or not
     * @return true if RecycleBin enable, false if is not available or not enable
     */
    var isRecycleBinEnabled = true
    var recycleBinUUID: UUID = UUID_ZERO
    var recycleBinChanged = DateInstant()
    var entryTemplatesGroup = UUID_ZERO
    var entryTemplatesGroupChanged = DateInstant()
    var historyMaxItems = DEFAULT_HISTORY_MAX_ITEMS
    var historyMaxSize = DEFAULT_HISTORY_MAX_SIZE
    var lastSelectedGroupUUID = UUID_ZERO
    var lastTopVisibleGroupUUID = UUID_ZERO
    var memoryProtection = MemoryProtectionConfig()
    val deletedObjects = HashSet<DeletedObject>()
    var publicCustomData = VariantDictionary()
    val customData = CustomData()

    val tagPool = Tags()

    var localizedAppName = "KeePassDX"

    constructor()

    /**
     * Create a new database with a root group
     */
    constructor(databaseName: String,
                rootName: String,
                templatesGroupName: String? = null) {
        name = databaseName
        kdbxVersion = FILE_VERSION_31
        val group = createGroup().apply {
            title = rootName
            icon.standard = getStandardIcon(IconImageStandard.FOLDER_ID)
        }
        rootGroup = group
        if (templatesGroupName != null) {
            val templatesGroup = mTemplateEngine.createNewTemplatesGroup(templatesGroupName)
            entryTemplatesGroup = templatesGroup.id
            entryTemplatesGroupChanged = templatesGroup.lastModificationTime
        }
    }

    override val version: String
        get() {
            val kdbxStringVersion = when(kdbxVersion) {
                FILE_VERSION_31 -> "3.1"
                FILE_VERSION_40 -> "4.0"
                FILE_VERSION_41 -> "4.1"
                else -> "UNKNOWN"
            }
            return "V2 - KDBX$kdbxStringVersion"
        }

    override val defaultFileExtension: String
        get() = ".kdbx"

    private open class NodeOperationHandler<T: NodeKDBXInterface> : NodeHandler<T>() {
        var containsCustomData = false
        override fun operate(node: T): Boolean {
            if (node.customData.isNotEmpty()) {
                containsCustomData = true
            }
            return true
        }
    }

    private inner class EntryOperationHandler: NodeOperationHandler<EntryKDBX>() {
        var passwordQualityEstimationDisabled = false
        override fun operate(node: EntryKDBX): Boolean {
            if (!node.qualityCheck) {
                passwordQualityEstimationDisabled = true
            }
            return super.operate(node)
        }
    }

    private inner class GroupOperationHandler: NodeOperationHandler<GroupKDBX>() {
        var containsTags = false
        override fun operate(node: GroupKDBX): Boolean {
            if (node.tags.isNotEmpty())
                containsTags = true
            return super.operate(node)
        }
    }

    fun getMinKdbxVersion(): UnsignedInt {
        val entryHandler = EntryOperationHandler()
        val groupHandler = GroupOperationHandler()
        rootGroup?.doForEachChildAndForIt(entryHandler, groupHandler)

        // https://keepass.info/help/kb/kdbx_4.1.html
        val containsGroupWithTag = groupHandler.containsTags
        val containsEntryWithPasswordQualityEstimationDisabled = entryHandler.passwordQualityEstimationDisabled
        val containsCustomIconWithNameOrLastModificationTime = iconsManager.containsCustomIconWithNameOrLastModificationTime()
        val containsHeaderCustomDataWithLastModificationTime = customData.containsItemWithLastModificationTime()

        // https://keepass.info/help/kb/kdbx_4.html
        // If AES is not use, it's at least 4.0
        val keyDerivationFunction = kdfEngine
        val kdfIsNotAes = keyDerivationFunction != null && keyDerivationFunction.uuid != AesKdf.CIPHER_UUID
        val containsHeaderCustomData = customData.isNotEmpty()
        val containsNodeCustomData = entryHandler.containsCustomData || groupHandler.containsCustomData

        // Check each condition to determine version
        return if (containsGroupWithTag
            || containsEntryWithPasswordQualityEstimationDisabled
            || containsCustomIconWithNameOrLastModificationTime
            || containsHeaderCustomDataWithLastModificationTime) {
            FILE_VERSION_41
        } else if (kdfIsNotAes
            || containsHeaderCustomData
            || containsNodeCustomData) {
            FILE_VERSION_40
        } else {
            FILE_VERSION_31
        }
    }

    val availableCompressionAlgorithms: List<CompressionAlgorithm> = listOf(
        CompressionAlgorithm.None,
        CompressionAlgorithm.GZip
    )

    fun changeBinaryCompression(oldCompression: CompressionAlgorithm,
                                newCompression: CompressionAlgorithm) {
        when (oldCompression) {
            CompressionAlgorithm.None -> {
                when (newCompression) {
                    CompressionAlgorithm.None -> {
                    }
                    CompressionAlgorithm.GZip -> {
                        // Only in databaseV3.1, in databaseV4 the header is zipped during the save
                        if (kdbxVersion.isBefore(FILE_VERSION_40)) {
                            compressAllBinaries()
                        }
                    }
                }
            }
            CompressionAlgorithm.GZip -> {
                // In databaseV4 the header is zipped during the save, so not necessary here
                if (kdbxVersion.isBefore(FILE_VERSION_40)) {
                    when (newCompression) {
                        CompressionAlgorithm.None -> {
                            decompressAllBinaries()
                        }
                        CompressionAlgorithm.GZip -> {
                        }
                    }
                } else {
                    decompressAllBinaries()
                }
            }
        }
    }

    private fun compressAllBinaries() {
        attachmentPool.doForEachBinary { _, binary ->
            try {
                // To compress, create a new binary with file
                binary.compress(binaryCache)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to compress $binary", e)
            }
        }
    }

    private fun decompressAllBinaries() {
        attachmentPool.doForEachBinary { _, binary ->
            try {
                binary.decompress(binaryCache)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to decompress $binary", e)
            }
        }
    }

    override var numberKeyEncryptionRounds: Long
        get() {
            val kdfEngine = kdfEngine
            var numKeyEncRounds: Long = 0
            if (kdfEngine != null && kdfParameters != null)
                numKeyEncRounds = kdfEngine.getKeyRounds(kdfParameters!!)
            return numKeyEncRounds
        }
        set(rounds) {
            val kdfEngine = kdfEngine
            if (kdfEngine != null && kdfParameters != null)
                kdfEngine.setKeyRounds(kdfParameters!!, rounds)
        }

    var memoryUsage: Long
        get() {
            val kdfEngine = kdfEngine
            return if (kdfEngine != null && kdfParameters != null) {
                kdfEngine.getMemoryUsage(kdfParameters!!)
            } else KdfEngine.UNKNOWN_VALUE
        }
        set(memory) {
            val kdfEngine = kdfEngine
            if (kdfEngine != null && kdfParameters != null)
                kdfEngine.setMemoryUsage(kdfParameters!!, memory)
        }

    var parallelism: Long
        get() {
            val kdfEngine = kdfEngine
            return if (kdfEngine != null && kdfParameters != null) {
                kdfEngine.getParallelism(kdfParameters!!)
            } else KdfEngine.UNKNOWN_VALUE
        }
        set(parallelism) {
            val kdfEngine = kdfEngine
            if (kdfEngine != null && kdfParameters != null)
                kdfEngine.setParallelism(kdfParameters!!, parallelism)
        }

    override val passwordEncoding: String
        get() = "UTF-8"

    private fun getGroupByUUID(groupUUID: UUID): GroupKDBX? {
        if (groupUUID == UUID_ZERO)
            return null
        return getGroupById(NodeIdUUID(groupUUID))
    }

    // Retrieve recycle bin in index
    val recycleBin: GroupKDBX?
        get() = getGroupByUUID(recycleBinUUID)

    val lastSelectedGroup: GroupKDBX?
        get() = getGroupByUUID(lastSelectedGroupUUID)

    val lastTopVisibleGroup: GroupKDBX?
        get() = getGroupByUUID(lastTopVisibleGroupUUID)

    override fun getStandardIcon(iconId: Int): IconImageStandard {
        return this.iconsManager.getIcon(iconId)
    }

    fun buildNewCustomIcon(customIconId: UUID? = null,
                           result: (IconImageCustom, BinaryData?) -> Unit) {
        // Create a binary file for a brand new custom icon
        addCustomIcon(customIconId, "", null, false, result)
    }

    fun addCustomIcon(customIconId: UUID? = null,
                      name: String,
                      lastModificationTime: DateInstant?,
                      smallSize: Boolean,
                      result: (IconImageCustom, BinaryData?) -> Unit) {
        iconsManager.addCustomIcon(customIconId, name, lastModificationTime, { uniqueBinaryId ->
            // Create a byte array for better performance with small data
            binaryCache.getBinaryData(uniqueBinaryId, smallSize)
        }, result)
    }

    fun removeCustomIcon(iconUuid: UUID) {
        iconsManager.removeCustomIcon(iconUuid, binaryCache)
    }

    fun isCustomIconBinaryDuplicate(binary: BinaryData): Boolean {
        return iconsManager.isCustomIconBinaryDuplicate(binary)
    }

    fun getCustomIcon(iconUuid: UUID): IconImageCustom? {
        return this.iconsManager.getIcon(iconUuid)
    }

    fun isTemplatesGroupEnabled(): Boolean {
        return entryTemplatesGroup != UUID_ZERO
    }

    fun enableTemplatesGroup(enable: Boolean, templatesGroupName: String) {
        // Create templates group only if a group with a valid name don't already exists
        val firstGroupWithValidName = getGroupIndexes().firstOrNull {
            it.title == templatesGroupName
        }
        if (enable) {
            val templatesGroup = firstGroupWithValidName
                ?: mTemplateEngine.createNewTemplatesGroup(templatesGroupName)
            entryTemplatesGroup = templatesGroup.id
        } else {
            removeTemplatesGroup()
        }
    }

    fun removeTemplatesGroup() {
        entryTemplatesGroup = UUID_ZERO
        mTemplateEngine.clearCache()
    }

    fun getTemplatesGroup(): GroupKDBX? {
        if (isTemplatesGroupEnabled()) {
            return getGroupById(entryTemplatesGroup)
        }
        return null
    }

    fun getTemplates(templateCreation: Boolean): List<Template> {
        return if (templateCreation)
            listOf(mTemplateEngine.getTemplateCreation())
        else
            mTemplateEngine.getTemplates()
    }

    fun getTemplate(entry: EntryKDBX): Template? {
        return mTemplateEngine.getTemplate(entry)
    }

    fun decodeEntryWithTemplateConfiguration(entryKDBX: EntryKDBX, entryIsTemplate: Boolean): EntryKDBX {
        return if (entryIsTemplate) {
            mTemplateEngine.decodeTemplateEntry(entryKDBX)
        } else {
            mTemplateEngine.removeMetaTemplateRecognitionFromEntry(entryKDBX)
        }
    }

    fun encodeEntryWithTemplateConfiguration(entryKDBX: EntryKDBX, entryIsTemplate: Boolean, template: Template): EntryKDBX {
        return if (entryIsTemplate) {
            mTemplateEngine.encodeTemplateEntry(entryKDBX)
        } else {
            mTemplateEngine.addMetaTemplateRecognitionToEntry(template, entryKDBX)
        }
    }

    /*
     * Search methods
     */

    fun getGroupById(id: UUID): GroupKDBX? {
        return this.getGroupById(NodeIdUUID(id))
    }

    fun getEntryById(id: UUID): EntryKDBX? {
        return this.getEntryById(NodeIdUUID(id))
    }

    fun getEntryByTitle(title: String, recursionLevel: Int): EntryKDBX? {
        return findEntry { entry ->
            entry.decodeTitleKey(recursionLevel).equals(title, true)
        }
    }

    fun getEntryByUsername(username: String, recursionLevel: Int): EntryKDBX? {
        return findEntry { entry ->
            entry.decodeUsernameKey(recursionLevel).equals(username, true)
        }
    }

    fun getEntryByURL(url: String, recursionLevel: Int): EntryKDBX? {
        return findEntry { entry ->
            entry.decodeUrlKey(recursionLevel).equals(url, true)
        }
    }

    fun getEntryByPassword(password: String, recursionLevel: Int): EntryKDBX? {
        return findEntry { entry ->
            entry.decodePasswordKey(recursionLevel).equals(password, true)
        }
    }

    fun getEntryByNotes(notes: String, recursionLevel: Int): EntryKDBX? {
        return findEntry { entry ->
            entry.decodeNotesKey(recursionLevel).equals(notes, true)
        }
    }

    fun getEntryByCustomData(customDataValue: String): EntryKDBX? {
        return findEntry { entry ->
            entry.customData.containsItemWithValue(customDataValue)
        }
    }

    /**
     * Retrieve the value of a field reference
     */
    fun getFieldReferenceValue(textReference: String, recursionLevel: Int): String {
        return mFieldReferenceEngine.compile(textReference, recursionLevel)
    }

    @Throws(IOException::class)
    public override fun getMasterKey(key: String?, keyInputStream: InputStream?): ByteArray {

        var masterKey = byteArrayOf()

        if (key != null && keyInputStream != null) {
            return getCompositeKey(key, keyInputStream)
        } else if (key != null) { // key.length() >= 0
            masterKey = getPasswordKey(key)
        } else if (keyInputStream != null) { // key == null
            masterKey = getFileKey(keyInputStream)
        }

        return HashManager.hashSha256(masterKey)
    }

    @Throws(IOException::class)
    fun makeFinalKey(masterSeed: ByteArray) {

        kdfParameters?.let { keyDerivationFunctionParameters ->
            val kdfEngine = getKdfEngineFromParameters(keyDerivationFunctionParameters)
                ?: throw IOException("Unknown key derivation function")

            var transformedMasterKey = kdfEngine.transform(masterKey, keyDerivationFunctionParameters)
            if (transformedMasterKey.size != 32) {
                transformedMasterKey = HashManager.hashSha256(transformedMasterKey)
            }

            val cmpKey = ByteArray(65)
            System.arraycopy(masterSeed, 0, cmpKey, 0, 32)
            System.arraycopy(transformedMasterKey, 0, cmpKey, 32, 32)
            finalKey = resizeKey(cmpKey, encryptionAlgorithm.cipherEngine.keyLength())

            val messageDigest: MessageDigest
            try {
                messageDigest = MessageDigest.getInstance("SHA-512")
                cmpKey[64] = 1
                hmacKey = messageDigest.digest(cmpKey)
            } catch (e: NoSuchAlgorithmException) {
                throw IOException("No SHA-512 implementation")
            } finally {
                Arrays.fill(cmpKey, 0.toByte())
            }
        }
    }

    private fun resizeKey(inBytes: ByteArray, cbOut: Int): ByteArray {
        if (cbOut == 0) return ByteArray(0)

        val messageDigest = if (cbOut <= 32) HashManager.getHash256() else HashManager.getHash512()
        messageDigest.update(inBytes, 0, 64)
        val hash: ByteArray = messageDigest.digest()

        if (cbOut == hash.size) {
            return hash
        }

        val ret = ByteArray(cbOut)
        if (cbOut < hash.size) {
            System.arraycopy(hash, 0, ret, 0, cbOut)
        } else {
            var pos = 0
            var r: Long = 0
            while (pos < cbOut) {
                val hmac: Mac
                try {
                    hmac = Mac.getInstance("HmacSHA256")
                } catch (e: NoSuchAlgorithmException) {
                    throw RuntimeException(e)
                }

                val pbR = longTo8Bytes(r)
                val part = hmac.doFinal(pbR)

                val copy = min(cbOut - pos, part.size)
                System.arraycopy(part, 0, ret, pos, copy)
                pos += copy
                r++

                Arrays.fill(part, 0.toByte())
            }
        }

        Arrays.fill(hash, 0.toByte())
        return ret
    }

    override fun loadXmlKeyFile(keyInputStream: InputStream): ByteArray? {
        try {
            val documentBuilderFactory = DocumentBuilderFactory.newInstance()

            // Disable certain unsecure XML-Parsing DocumentBuilderFactory features
            try {
                documentBuilderFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            } catch (e : ParserConfigurationException) {
                Log.w(TAG, "Unable to add FEATURE_SECURE_PROCESSING to prevent XML eXternal Entity injection (XXE)")
            }

            val documentBuilder = documentBuilderFactory.newDocumentBuilder()
            val doc = documentBuilder.parse(keyInputStream)

            var xmlKeyFileVersion = 1F

            val docElement = doc.documentElement
            val keyFileChildNodes = docElement.childNodes
            // <KeyFile> Root node
            if (docElement == null
                    || !docElement.nodeName.equals(XML_NODE_ROOT_NAME, ignoreCase = true)) {
                return null
            }
            if (keyFileChildNodes.length < 2)
                return null
            for (keyFileChildPosition in 0 until keyFileChildNodes.length) {
                val keyFileChildNode = keyFileChildNodes.item(keyFileChildPosition)
                // <Meta>
                if (keyFileChildNode.nodeName.equals(XML_NODE_META_NAME, ignoreCase = true)) {
                    val metaChildNodes = keyFileChildNode.childNodes
                    for (metaChildPosition in 0 until metaChildNodes.length) {
                        val metaChildNode = metaChildNodes.item(metaChildPosition)
                        // <Version>
                        if (metaChildNode.nodeName.equals(XML_NODE_VERSION_NAME, ignoreCase = true)) {
                            val versionChildNodes = metaChildNode.childNodes
                            for (versionChildPosition in 0 until versionChildNodes.length) {
                                val versionChildNode = versionChildNodes.item(versionChildPosition)
                                if (versionChildNode.nodeType == Node.TEXT_NODE) {
                                    val versionText = versionChildNode.textContent.removeSpaceChars()
                                    try {
                                        xmlKeyFileVersion = versionText.toFloat()
                                        Log.i(TAG, "Reading XML KeyFile version : $xmlKeyFileVersion")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "XML Keyfile version cannot be read : $versionText")
                                    }
                                }
                            }
                        }
                    }
                }
                // <Key>
                if (keyFileChildNode.nodeName.equals(XML_NODE_KEY_NAME, ignoreCase = true)) {
                    val keyChildNodes = keyFileChildNode.childNodes
                    for (keyChildPosition in 0 until keyChildNodes.length) {
                        val keyChildNode = keyChildNodes.item(keyChildPosition)
                        // <Data>
                        if (keyChildNode.nodeName.equals(XML_NODE_DATA_NAME, ignoreCase = true)) {
                            var hashString : String? = null
                            if (keyChildNode.hasAttributes()) {
                                val dataNodeAttributes = keyChildNode.attributes
                                hashString = dataNodeAttributes
                                        .getNamedItem(XML_ATTRIBUTE_DATA_HASH).nodeValue
                            }
                            val dataChildNodes = keyChildNode.childNodes
                            for (dataChildPosition in 0 until dataChildNodes.length) {
                                val dataChildNode = dataChildNodes.item(dataChildPosition)
                                if (dataChildNode.nodeType == Node.TEXT_NODE) {
                                    val dataString = dataChildNode.textContent.removeSpaceChars()
                                    when (xmlKeyFileVersion) {
                                        1F -> {
                                            // No hash in KeyFile XML version 1
                                            return Base64.decode(dataString, BASE_64_FLAG)
                                        }
                                        2F -> {
                                            return if (hashString != null
                                                    && checkKeyFileHash(dataString, hashString)) {
                                                Log.i(TAG, "Successful key file hash check.")
                                                Hex.decodeHex(dataString.toCharArray())
                                            } else {
                                                Log.e(TAG, "Unable to check the hash of the key file.")
                                                null
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    private fun checkKeyFileHash(data: String, hash: String): Boolean {
        var success = false
        try {
            // hexadecimal encoding of the first 4 bytes of the SHA-256 hash of the key.
            val dataDigest = HashManager.hashSha256(Hex.decodeHex(data.toCharArray()))
                    .copyOfRange(0, 4).toHexString()
            success = dataDigest == hash
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return success
    }

    override fun newGroupId(): NodeIdUUID {
        var newId: NodeIdUUID
        do {
            newId = NodeIdUUID()
        } while (isGroupIdUsed(newId))

        return newId
    }

    override fun newEntryId(): NodeIdUUID {
        var newId: NodeIdUUID
        do {
            newId = NodeIdUUID()
        } while (isEntryIdUsed(newId))

        return newId
    }

    override fun createGroup(): GroupKDBX {
        return GroupKDBX()
    }

    override fun createEntry(): EntryKDBX {
        return EntryKDBX()
    }

    override fun rootCanContainsEntry(): Boolean {
        return true
    }

    override fun isInRecycleBin(group: GroupKDBX): Boolean {
        // To keep compatibility with old V1 databases
        var currentGroup: GroupKDBX? = group
        while (currentGroup != null) {
            if (currentGroup.parent == rootGroup
                    && currentGroup.title.equals(BACKUP_FOLDER_TITLE, ignoreCase = true)) {
                return true
            }
            currentGroup = currentGroup.parent
        }

        return if (recycleBin == null)
            false
        else if (!isRecycleBinEnabled)
            false
        else
            group.isContainedIn(recycleBin!!)
    }

    /**
     * Ensure that the recycle bin tree exists, if enabled and create it
     * if it doesn't exist
     */
    fun ensureRecycleBinExists(resources: Resources) {
        if (recycleBin == null) {
            // Create recycle bin only if a group with a valid name don't already exists
            val firstGroupWithValidName = getGroupIndexes().firstOrNull {
                it.title == resources.getString(R.string.recycle_bin)
            }
            val recycleBinGroup = if (firstGroupWithValidName == null) {
                val newRecycleBinGroup = createGroup().apply {
                    title = resources.getString(R.string.recycle_bin)
                    icon.standard = getStandardIcon(IconImageStandard.TRASH_ID)
                    enableAutoType = false
                    enableSearching = false
                    isExpanded = false
                }
                addGroupTo(newRecycleBinGroup, rootGroup)
                newRecycleBinGroup
            } else {
                firstGroupWithValidName
            }
            recycleBinUUID = recycleBinGroup.id
            recycleBinChanged = DateInstant()
        }
    }

    fun removeRecycleBin() {
        if (recycleBin != null) {
            recycleBinUUID = UUID_ZERO
        }
    }

    /**
     * Define if a Node must be delete or recycle when remove action is called
     * @param node Node to remove
     * @return true if node can be recycle, false elsewhere
     */
    fun canRecycle(node: NodeVersioned<*, GroupKDBX, EntryKDBX>): Boolean {
        if (!isRecycleBinEnabled)
            return false
        if (recycleBin == null)
            return false
        if (node is GroupKDBX
                && recycleBin!!.isContainedIn(node))
            return false
        if (!node.isContainedIn(recycleBin!!))
            return true
        return false
    }

    fun getDeletedObject(nodeId: NodeId<UUID>): DeletedObject? {
        return deletedObjects.find { it.uuid == nodeId.id }
    }

    fun addDeletedObject(deletedObject: DeletedObject) {
        this.deletedObjects.add(deletedObject)
    }

    fun addDeletedObject(objectId: UUID) {
        addDeletedObject(DeletedObject(objectId))
    }

    override fun addEntryTo(newEntry: EntryKDBX, parent: GroupKDBX?) {
        super.addEntryTo(newEntry, parent)
        tagPool.put(newEntry.tags)
        mFieldReferenceEngine.clear()
    }

    override fun updateEntry(entry: EntryKDBX) {
        super.updateEntry(entry)
        tagPool.put(entry.tags)
        mFieldReferenceEngine.clear()
    }

    override fun removeEntryFrom(entryToRemove: EntryKDBX, parent: GroupKDBX?) {
        super.removeEntryFrom(entryToRemove, parent)
        // Do not remove tags from pool, it's only in temp memory
        mFieldReferenceEngine.clear()
    }

    fun containsPublicCustomData(): Boolean {
        return publicCustomData.size() > 0
    }

    fun buildNewBinaryAttachment(smallSize: Boolean,
                                 compression: Boolean,
                                 protection: Boolean,
                                 binaryPoolId: Int? = null): BinaryData {
        return attachmentPool.put(binaryPoolId) { uniqueBinaryId ->
            binaryCache.getBinaryData(uniqueBinaryId, smallSize, compression, protection)
        }.binary
    }

    fun removeUnlinkedAttachment(binary: BinaryData, clear: Boolean) {
        val listBinaries = ArrayList<BinaryData>()
        listBinaries.add(binary)
        removeUnlinkedAttachments(listBinaries, clear)
    }

    fun removeUnlinkedAttachments(clear: Boolean) {
        removeUnlinkedAttachments(emptyList(), clear)
    }

    private fun removeUnlinkedAttachments(binaries: List<BinaryData>, clear: Boolean) {
        // TODO check in icon pool
        // Build binaries to remove with all binaries known
        val binariesToRemove = ArrayList<BinaryData>()
        if (binaries.isEmpty()) {
            attachmentPool.doForEachBinary { _, binary ->
                binariesToRemove.add(binary)
            }
        } else {
            binariesToRemove.addAll(binaries)
        }
        // Remove binaries from the list
        rootGroup?.doForEachChild(object : NodeHandler<EntryKDBX>() {
            override fun operate(node: EntryKDBX): Boolean {
                node.getAttachments(attachmentPool, true).forEach {
                    binariesToRemove.remove(it.binaryData)
                }
                return binariesToRemove.isNotEmpty()
            }
        }, null)
        // Effective removing
        binariesToRemove.forEach {
            try {
                attachmentPool.remove(it)
                if (clear)
                    it.clear(binaryCache)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to clean binaries", e)
            }
        }
    }

    override fun validatePasswordEncoding(password: String?, containsKeyFile: Boolean): Boolean {
        if (password == null)
            return true
        return super.validatePasswordEncoding(password, containsKeyFile)
    }

    override fun clearIndexes() {
        try {
            super.clearIndexes()
            mFieldReferenceEngine.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to clear cache", e)
        }
    }

    companion object {
        val TYPE = DatabaseKDBX::class.java
        private val TAG = DatabaseKDBX::class.java.name

        private const val DEFAULT_HISTORY_MAX_ITEMS = 10 // -1 unlimited
        private const val DEFAULT_HISTORY_MAX_SIZE = (6 * 1024 * 1024).toLong() // -1 unlimited

        private const val XML_NODE_ROOT_NAME = "KeyFile"
        private const val XML_NODE_META_NAME = "Meta"
        private const val XML_NODE_VERSION_NAME = "Version"
        private const val XML_NODE_KEY_NAME = "Key"
        private const val XML_NODE_DATA_NAME = "Data"
        private const val XML_ATTRIBUTE_DATA_HASH = "Hash"

        const val BASE_64_FLAG = Base64.NO_WRAP
    }
}