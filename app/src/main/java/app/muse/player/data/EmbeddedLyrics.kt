package app.muse.player.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/** Bounded, streaming embedded lyric reader. It never loads the audio stream. */
object EmbeddedLyrics {
    private const val MAX = 4 * 1024 * 1024
    private const val MAX_FRAME = 2 * 1024 * 1024
    suspend fun read(resolver: ContentResolver, uri: Uri): List<LyricLine> {
        return runCatching { resolver.openInputStream(uri)?.use { parse(it) } ?: emptyList() }
            .getOrDefault(emptyList())
    }

    suspend fun parse(input: InputStream): List<LyricLine> {
        val s = BufferedInputStream(input, 32 * 1024)
        val head = ByteArray(4); if (s.read(head) < 4) return emptyList()
        val out = when {
            head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> parseId3(s, head)
            head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() && head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte() -> parseFlac(s)
            else -> emptyList() // MP4 atoms require random-access atom walking; do not guess from arbitrary bytes
        }
        currentCoroutineContext().ensureActive()
        return out
    }

    private suspend fun parseId3(s: InputStream, head: ByteArray): List<LyricLine> {
        val hdr = ByteArray(6); if (s.read(hdr) != 6) return emptyList()
        val version = head[3].toInt() and 255; val flags = hdr[1].toInt(); val size = synchsafe(hdr, 2).coerceAtMost(MAX)
        if (version !in 2..4) return emptyList()
        val body = readAtMost(s, size); val result = ArrayList<LyricLine>(); var p = 0
        while (p + (if (version == 2) 6 else 10) <= body.size) {
            currentCoroutineContext().ensureActive()
            val id: String; val n: Int; val start: Int
            if (version == 2) { id = String(body, p, 3, Charsets.ISO_8859_1); n = u24(body, p + 3); start = p + 6 }
            else { id = String(body, p, 4, Charsets.ISO_8859_1); n = if (version == 4) synchsafe(body, p + 4) else u32(body, p + 4); start = p + 10 }
            if (n <= 0 || n > MAX_FRAME || start + n > body.size) break
            val frame = body.copyOfRange(start, start + n)
            when (id) {
                "USLT", "ULT" -> parseUnsync(frame)?.let { result.addAll(parseEmbeddedText(it)) }
                "SYLT" -> result.addAll(parseSynced(frame))
                "TXXX", "TXX" -> parseTxxx(frame)?.takeIf { it.first.contains("LYRIC", true) }?.let { result.addAll(parseEmbeddedText(it.second)) }
            }
            p = start + n
        }
        return result.sortedBy { it.start }
    }

    private fun parseUnsync(b: ByteArray): String? {
        if (b.isEmpty()) return null; val enc = b[0].toInt() and 255; if (b.size < 4) return null
        val descEnd = terminator(b, 4, enc); val textStart = if (descEnd < 0) 4 else descEnd + termLen(enc)
        return decode(b, textStart, b.size - textStart, enc)
    }
    private fun parseTxxx(b: ByteArray): Pair<String, String>? {
        if (b.isEmpty()) return null; val enc = b[0].toInt() and 255; val e = terminator(b, 1, enc); if (e < 0) return null
        val valueAt = e + termLen(enc); return decode(b, 1, e - 1, enc) to decode(b, valueAt, b.size - valueAt, enc)
    }
    private fun parseEmbeddedText(t: String): List<LyricLine> = LyricParser.parseLrc(t).ifEmpty { if (t.isBlank()) emptyList() else listOf(LyricLine(0, Long.MAX_VALUE, t.trim())) }

    private fun parseSynced(b: ByteArray): List<LyricLine> {
        if (b.size < 7) return emptyList()
        val enc = b[0].toInt() and 255; val desc = terminator(b, 6, enc); if (desc < 0) return emptyList()
        var p = desc + termLen(enc); val rows = ArrayList<Pair<Long,String>>()
        while (p < b.size) { val e = terminator(b,p,enc); if (e < 0 || e + termLen(enc) + 4 > b.size) break
            val text = decode(b,p,e-p,enc); p=e+termLen(enc); val time=u32(b,p).toLong() and 0xffffffffL; p+=4
            if(text.isNotBlank()) rows += time to text }
        return rows.sortedBy{it.first}.mapIndexed{i,x->LyricLine(x.first,rows.getOrNull(i+1)?.first ?: x.first+5000,x.second)}
    }

    private suspend fun parseFlac(s: InputStream): List<LyricLine> {
        var last=false; val out=ArrayList<LyricLine>()
        while(!last){ currentCoroutineContext().ensureActive(); val h=s.read(); if(h<0)break; last=h and 0x80!=0; val n=readInt24(s); if(n<0||n>MAX_FRAME)break
            if((h and 0x7f)==4){ val b=readAtMost(s,n); if(b.size<8)continue; var p=0; val vendor=le32(b,p); p+=4; if(vendor<0||p+vendor+4>b.size)continue; p+=vendor; val count=le32(b,p);p+=4
                repeat(count.coerceAtMost(10000)){ if(p+4>b.size)return@repeat; val kl=le32(b,p);p+=4; if(kl<0||p+kl+4>b.size)return@repeat; val k=String(b,p,kl,Charsets.UTF_8);p+=kl; val vl=le32(b,p);p+=4; if(vl<0||p+vl>b.size)return@repeat; val v=String(b,p,vl,Charsets.UTF_8);p+=vl; if(k.contains("LYRIC",true))out.addAll(parseEmbeddedText(v)) }
            } else skipFully(s,n)
        }; return out
    }

    private suspend fun parseMp4(s: InputStream, initial: ByteArray): List<LyricLine> {
        val b = ByteArrayOutputStream(); b.write(initial); var total=4; while(total<MAX){ currentCoroutineContext().ensureActive(); val x=ByteArray(64*1024); val n=s.read(x); if(n<=0)break; b.write(x,0,n); total+=n }
        val all=b.toByteArray(); val out=ArrayList<LyricLine>(); val needles=arrayOf("©lyr","lyrics","LYRICS")
        for (needle in needles) { var p=0; while(true){ val i=indexOf(all,needle.toByteArray(),p); if(i<0)break; val end=(i+needle.length+MAX_FRAME).coerceAtMost(all.size); val text=all.copyOfRange(i+needle.length,end).toString(Charsets.UTF_8).substringBefore('\u0000').trim(); if(text.isNotBlank()) out.addAll(LyricParser.parseLrc(text)); p=i+needle.length } }
        return out
    }
    private fun readAtMost(i:InputStream,n:Int):ByteArray { val out=ByteArrayOutputStream(); val b=ByteArray(32*1024); var left=n; while(left>0){val k=i.read(b,0,minOf(left,b.size));if(k<=0)break;out.write(b,0,k);left-=k};return out.toByteArray() }
    private fun skipFully(i:InputStream,n:Int){var x=n.toLong();while(x>0){val k=i.skip(x);if(k<=0){if(i.read()<0)break else x--}else x-=k}}
    private fun readInt24(i:InputStream): Int {
        val a = i.read(); val b = i.read(); val c = i.read()
        return if (a < 0 || b < 0 || c < 0) -1 else (a shl 16) or (b shl 8) or c
    }
    private fun synchsafe(b:ByteArray,p:Int): Int = ((b[p].toInt() and 127) shl 21) or ((b[p+1].toInt() and 127) shl 14) or ((b[p+2].toInt() and 127) shl 7) or (b[p+3].toInt() and 127)
    private fun u32(b:ByteArray,p:Int): Int = ((b[p].toInt() and 255) shl 24) or ((b[p+1].toInt() and 255) shl 16) or ((b[p+2].toInt() and 255) shl 8) or (b[p+3].toInt() and 255)
    private fun u24(b:ByteArray,p:Int): Int = ((b[p].toInt() and 255) shl 16) or ((b[p+1].toInt() and 255) shl 8) or (b[p+2].toInt() and 255)
    private fun le32(b:ByteArray,p:Int):Int=(b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8) or ((b[p+2].toInt() and 255) shl 16) or ((b[p+3].toInt() and 255) shl 24)
    private fun u32le16(b:ByteArray,p:Int):Int=(b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8)
    private fun readLeString(b:ByteArray,p:Int):Pair<String,Int>? {if(p+4>b.size)return null;val n=u32le16(b,p);if(n<0||p+2+n>b.size)return null;return String(b,p+2,n,Charsets.UTF_8) to p+2+n}
    private fun termLen(enc:Int)=if(enc==1||enc==2)2 else 1
    private fun terminator(b:ByteArray,p:Int,enc:Int):Int {val l=termLen(enc);var i=p;while(i+l<=b.size){if((0 until l).all{b[i+it].toInt()==0})return i;i+=l};return -1}
    private fun decode(b:ByteArray,p:Int,n:Int,enc:Int):String {if(p<0||n<=0||p+n>b.size)return "";val cs=when(enc){1->Charsets.UTF_16;2->Charsets.UTF_16BE;3->Charsets.UTF_8;else->Charsets.ISO_8859_1};return String(b,p,n,cs).trim()}
    private fun indexOf(a:ByteArray,x:ByteArray,from:Int):Int { outer@for(i in from..a.size-x.size){for(j in x.indices)if(a[i+j]!=x[j])continue@outer;return i};return -1 }
}
