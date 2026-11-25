package app.muse.player.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nevoit.sfcompose.symbols.SFSymbol
import com.nevoit.sfcompose.symbols.SFSymbols
import com.nevoit.sfcompose.symbols.StaticSymbol
import com.nevoit.sfcompose.symbols.SymbolDisplayMode

/**
 * Muse 的图标全部来自 SF Symbols 的公开目录（io.github.nevodev:sfcompose-catalog）。
 *
 * 这里只列应用实际用到的条目：SFCompose 的 Gradle 插件会扫描这些引用，只把被引用的
 * 符号数据打进 APK，所以不用（也不该）把整套目录塞进安装包。
 * 命名沿用 Apple 的语义：线描用于未选中，`.Fill` 变体用于选中/强调。
 */
object Glyphs {
    val Play = SFSymbols.Play.Fill
    val Pause = SFSymbols.Pause.Fill
    val Next = SFSymbols.Forward.Fill
    val Prev = SFSymbols.Backward.Fill
    val Shuffle = SFSymbols.Shuffle
    val Repeat = SFSymbols.Repeat
    val RepeatOne = SFSymbols.Repeat.One
    val Note = SFSymbols.Music.Note
    val Lyrics = SFSymbols.Quote.Bubble
    val LyricsFill = SFSymbols.Quote.Bubble.Fill
    val Search = SFSymbols.Magnifyingglass
    val Explore = SFSymbols.Safari
    val Library = SFSymbols.Square.Stack
    val LibraryFill = SFSymbols.Square.Stack.Fill
    val Listen = SFSymbols.Play.Circle
    val ListenFill = SFSymbols.Play.Circle.Fill
    val Group = SFSymbols.Person.Two
    val Together = SFSymbols.Shareplay
    val TogetherFill = SFSymbols.Shareplay
    val Loop = SFSymbols.Repeat
    val Person = SFSymbols.Person.Fill
    val PersonCircle = SFSymbols.Person.Crop.Circle
    val PersonCircleFill = SFSymbols.Person.Crop.Circle.Fill
    val Back = SFSymbols.Chevron.Backward
    val Down = SFSymbols.Chevron.Down
    val Copy = SFSymbols.Document.On.Document
    val Settings = SFSymbols.Gearshape.Fill
    val Heart = SFSymbols.Heart
    val Radar = SFSymbols.Antenna.Radiowaves.Left.And.Right
    val Download = SFSymbols.Arrow.Down.Circle.Fill
    val Info = SFSymbols.Info.Circle.Fill
    val Forward = SFSymbols.Chevron.Forward
    val Share = SFSymbols.Square.And.Arrow.Up
    val Open = SFSymbols.Arrow.Up.Right
}

/**
 * 画一个 SF Symbol。旧版本这里的入参是 ImageVector，换成符号条目后调用点写法不变。
 *
 * `pointSize` 是槽位高度；SquareOverflow 下槽位是等宽高方块，宽度和原来的 Icon 一致，
 * 横向更宽的符号允许轻微溢出，不会被裁切。
 */
@Composable
fun Glyph(
    icon: StaticSymbol,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    SFSymbol(
        symbol = icon,
        contentDescription = null,
        modifier = modifier,
        pointSize = size,
        tint = tint,
        displayMode = SymbolDisplayMode.SquareOverflow,
    )
}
