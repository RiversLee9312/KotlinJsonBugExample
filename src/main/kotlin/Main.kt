import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

@Serializable
class TestData(
    var data0: String,
    var data1: Long,
    var sub: TestSubData
){
    override fun toString(): String {
        return "(data0:$data0 data1:$data1 sub:$sub)"
    }

    override fun equals(other: Any?): Boolean {
        return toString() == other.toString()
    }

    override fun hashCode(): Int {
        var result = data0.hashCode()
        result = 31 * result + data1.hashCode()
        result = 31 * result + sub.hashCode()
        return result
    }
}

@Serializable
class TestSubData(
    var data0: String,
    var data1: Long
){
    override fun toString(): String {
        return "(data0:$data0 data1:$data1)"
    }

    override fun equals(other: Any?): Boolean {
        return toString() == other.toString()
    }

    override fun hashCode(): Int {
        var result = data0.hashCode()
        result = 31 * result + data1.hashCode()
        return result
    }
}

@OptIn(ExperimentalSerializationApi::class)
fun main() {
    val testData = TestData(
        "Hello,World".repeat(50),
        110234L,
        TestSubData(
            "Sub",
            1234L
        )
    )
    try {
        val buffer = ResizableBuffer()
        buffer.outputStream().use {
            Json.encodeToStream(testData,it)
        }
        val newData: TestData = buffer.inputStream().use {
            Json.decodeFromStream(it)
        }
        println(newData == testData)
    }catch (e:Exception){
        println(e)
    }
    val tempFile = kotlin.io.path.createTempFile().toFile()
    tempFile.outputStream().use {
        Json.encodeToStream(testData, it)
    }
    var newData: TestData = tempFile.inputStream().use {
        Json.decodeFromStream(it)
    }
    println(newData == testData)
    tempFile.delete()
    val buffer = ResizableBuffer()
    buffer.outputStream().use {
        Json.encodeToStream(testData,it)
    }
    newData = buffer.inputStream().use {
        Json.decodeFromStream(it)
    }
    println(newData == testData)
}