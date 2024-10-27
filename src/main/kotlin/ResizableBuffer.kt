import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

//Read and write operations are thread-safe here.
class ResizableBuffer(
    val chunkSize: Int = DEFAULT_CHUNK_SIZE
) : Closeable {
    companion object {
        const val DEFAULT_CHUNK_SIZE = 256
    }

    private val chunks = mutableListOf<ByteArray>()
    private val lock = ReadWriteMutex()

    private data class ActualOffset(
        var chunkIndex: Int,
        var arrayIndex: Int
    )

    init {
        if (chunkSize <= 0) {
            throw IllegalArgumentException("Illegal chunk size:$chunkSize")
        }
        runBlocking {
            lock.write.withLock {
                chunks.add(ByteArray(chunkSize){0})
            }
        }

    }

    //first is chunk index,second is array index
    private fun getActualOffset(originOffset: Int): ActualOffset = ActualOffset(
        chunkIndex = originOffset / chunkSize,
        arrayIndex = originOffset % chunkSize
    )

    private fun resizeIfNeeded(actualOffset: ActualOffset) {
        //we won't try to get write lock here,
        //because the caller will do it.
        if (actualOffset.chunkIndex >= chunks.size) {
            var chunksNeeded = actualOffset.chunkIndex - chunks.size + 1
            while (chunksNeeded > 0) {
                chunks.add(ByteArray(chunkSize){0})
                chunksNeeded--
            }
        }
    }

    private var closed = false
    private val closedLock = ReadWriteMutex()
    private fun checkIfClosed() {
        runBlocking {
            closedLock.read.withLock {
                if (closed) {
                    throw IllegalStateException("already closed.")
                }
            }
        }
    }

    override fun close() {
        runBlocking {
            lock.write.withLock {
                checkIfClosed()
                chunks.clear()
                closedLock.write.withLock {
                    closed = true
                }
            }
        }
    }

    private fun updateUsedSize(actualOffset: ActualOffset){
        usedSize = (actualOffset.chunkIndex*chunkSize + actualOffset.arrayIndex + 1).coerceAtLeast(usedSize)
    }

    fun write(
        data: ByteArray,
        offset: Int,
        sourceOffset:Int,
        length: Int
    ) {
        checkIfClosed()
        if ((length+sourceOffset)>data.size) {
            throw IllegalArgumentException("Data size is smaller than length")
        }
        runBlocking {
            lock.write.withLock {
                val endActualOffset = getActualOffset(offset+length-1)
                resizeIfNeeded(endActualOffset)
                val startActualOffset = getActualOffset(offset)
                var bytesToWrite = length
                var currentChunkIndex = startActualOffset.chunkIndex
                var currentArrayIndex = startActualOffset.arrayIndex
                while (bytesToWrite > 0) {
                    var bytesCanWrite = chunkSize - currentArrayIndex
                    if (bytesCanWrite > bytesToWrite) {
                        bytesCanWrite = bytesToWrite
                    }
                    val startIndex = length - bytesToWrite + sourceOffset
                    data.copyInto(
                        chunks[currentChunkIndex],
                        currentArrayIndex,
                        startIndex,
                        startIndex + bytesCanWrite
                    )
                    currentChunkIndex++
                    currentArrayIndex = 0
                    bytesToWrite -= bytesCanWrite
                }
                updateUsedSize(endActualOffset)
            }
        }

    }

    fun writeByte(data: Byte, offset: Int) {
        checkIfClosed()
        runBlocking {
            lock.write.withLock {
                val actualOffset = getActualOffset(offset)
                resizeIfNeeded(actualOffset)
                chunks[actualOffset.chunkIndex][actualOffset.arrayIndex] = data
                updateUsedSize(actualOffset)
            }
        }

    }

    //returns -1 if offset is out of range
    fun readByte(offset: Int): Int {
        checkIfClosed()
        return runBlocking {
            lock.read.withLock {
                if (offset >= usedSize) {
                    -1
                } else {
                    val actualOffset = getActualOffset(offset)
                    chunks[actualOffset.chunkIndex][actualOffset.arrayIndex].toInt()
                }
            }
        }
    }

    //returns the count of the bytes that are read
    //or -1 if the offset is out of range
    fun read(buffer: ByteArray, offset: Int, destinationOffset:Int, len: Int): Int {
        checkIfClosed()
        return runBlocking {
            lock.read.withLock {
                var bytesRead: Int
                if(buffer.size<(destinationOffset+len)){
                    throw ArrayIndexOutOfBoundsException(destinationOffset+len-1)
                }
                if (offset >= usedSize) {
                    bytesRead = -1
                } else {
                    val startActualOffset = getActualOffset(offset)
                    var bytesToRead = len.coerceAtMost(usedSize-offset)
                    var currentChunkIndex = startActualOffset.chunkIndex
                    var currentArrayIndex = startActualOffset.arrayIndex
                    bytesRead = 0
                    while ((bytesToRead > 0) and (currentChunkIndex < chunks.size)) {
                        val bytesCanRead = bytesToRead.coerceAtMost(chunkSize-currentArrayIndex)
                        val currentDestinationIndex = len - bytesToRead + destinationOffset
                        chunks[currentChunkIndex].copyInto(
                            buffer,
                            currentDestinationIndex,
                            currentArrayIndex,
                            currentArrayIndex + bytesCanRead
                        )
                        bytesToRead -= bytesCanRead
                        currentChunkIndex++
                        currentArrayIndex = 0
                        bytesRead += bytesCanRead
                    }
                    //Log.e(TAG, "read: $bytesRead bytes read,$usedSize bytes used", )
                }
                bytesRead
            }
        }
    }

    val allocatedSize
        get() = runBlocking{
            lock.read.withLock {
                checkIfClosed()
                chunks.size * chunkSize
            }
        }

    private var usedSize = 0

    val size
        get() = runBlocking{
            lock.read.withLock {
                checkIfClosed()
                usedSize
            }
        }

    fun clear() {
        runBlocking{
            lock.write.withLock {
                checkIfClosed()
                chunks.clear()
                usedSize = 0
                chunks.add(ByteArray(chunkSize){0})
            }
        }
    }

    fun inputStream(): ResizableBufferInputStream {
        checkIfClosed()
        return ResizableBufferInputStream(buffer = this)
    }

    fun outputStream(): ResizableBufferOutputStream {
        checkIfClosed()
        return ResizableBufferOutputStream(
            chunkSize = chunkSize,
            buffer = this
        )
    }
}

class ResizableBufferInputStream(
    val buffer: ResizableBuffer
) : InputStream() {
    private val closedLock = ReadWriteMutex()
    private var closed = false
    private fun throwIfClosed(e: Exception = IllegalStateException("Already closed")) {
        runBlocking{
            closedLock.read.withLock {
                if (closed) {
                    throw e
                }
            }
        }
    }

    override fun close() {
        throwIfClosed()
        runBlocking{
            closedLock.write.withLock {
                closed = true
            }
        }
        super.close()
    }
    private var position = 0
    private val positionLock = Mutex()
    override fun read(): Int {
        throwIfClosed()
        return runBlocking {
            positionLock.withLock {
                position++
                buffer.readByte(position)
            }
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        throwIfClosed()
        return runBlocking {
            positionLock.withLock {
                run {
                    val bytesRead = buffer.read(b, position,off, len)
                    if(bytesRead>=0){
                        position+=bytesRead
                    }
                    bytesRead
                }
            }
        }

    }

    override fun reset() {
        throwIfClosed()
        runBlocking {
            positionLock.withLock {
                position=0
            }
        }
        super.reset()
    }


}

class ResizableBufferOutputStream(
    val chunkSize: Int = ResizableBuffer.DEFAULT_CHUNK_SIZE,
    val buffer: ResizableBuffer = ResizableBuffer(chunkSize)
) : OutputStream() {
    private var currentPosition = 0
    private val positionLock = Mutex()
    private var closed = false
    private val closedLock = ReadWriteMutex()
    private fun throwIfClosed(e: Exception = IllegalStateException("Already closed")) {
        runBlocking {
            closedLock.read.withLock {
                if (closed) {
                    throw e
                }
            }
        }
    }

    override fun write(b: Int) {
        throwIfClosed()
        runBlocking {
            positionLock.withLock {
                buffer.writeByte(b.toByte(), currentPosition)
                currentPosition++
            }
        }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        throwIfClosed()
        runBlocking {
            positionLock.withLock {
                buffer.write(b,currentPosition, off, len)
                currentPosition+=len
            }
        }
    }

    override fun close() {
        throwIfClosed()
        runBlocking{
            closedLock.write.withLock {
                closed = true
            }
        }
        super.close()
    }
}