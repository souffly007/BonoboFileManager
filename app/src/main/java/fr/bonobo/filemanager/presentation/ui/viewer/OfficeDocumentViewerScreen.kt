package fr.bonobo.filemanager.presentation.ui.viewer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

private data class SheetData(val name: String, val rows: List<List<String>>)
private data class SlideData(val name: String, val text: String)
private sealed interface OfficeContent {
    data class Text(val value: String) : OfficeContent
    data class Workbook(val sheets: List<SheetData>) : OfficeContent
    data class Presentation(val slides: List<SlideData>) : OfficeContent
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficeDocumentViewerScreen(path: String, onNavigateBack: () -> Unit) {
    val file = remember(path) { File(path) }
    var content by remember { mutableStateOf<OfficeContent?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(path) {
        val result = withContext(Dispatchers.IO) { runCatching { readOfficeDocument(file) } }
        result.onSuccess { content = it }.onFailure { error = it.message ?: "Document illisible" }
        loading = false
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(file.name, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Retour") } }
        )
    }) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding)) { CircularProgressIndicator(Modifier.padding(24.dp)) }
            error != null -> Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                Text("Impossible de lire ce document", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp)); Text(error.orEmpty())
            }
            content is OfficeContent.Workbook -> WorkbookView((content as OfficeContent.Workbook).sheets, Modifier.padding(padding))
            content is OfficeContent.Presentation -> PresentationView((content as OfficeContent.Presentation).slides, Modifier.padding(padding))
            else -> {
                val text = (content as? OfficeContent.Text)?.value.orEmpty()
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (text.isBlank()) Text("Ce document ne contient pas de texte lisible.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun WorkbookView(sheets: List<SheetData>, modifier: Modifier = Modifier) {
    if (sheets.isEmpty()) {
        Box(modifier.fillMaxSize().padding(20.dp)) { Text("Ce classeur ne contient aucune feuille lisible.") }
        return
    }
    var selected by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    val sheet = sheets[selected.coerceIn(sheets.indices)]
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Feuille : ${sheet.name}") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                sheets.forEachIndexed { index, s ->
                    DropdownMenuItem(text = { Text(s.name) }, onClick = { selected = index; expanded = false })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val hScroll = rememberScrollState()
        val vScroll = rememberScrollState()
        Column(Modifier.fillMaxSize().horizontalScroll(hScroll).verticalScroll(vScroll)) {
            sheet.rows.forEachIndexed { rowIndex, row ->
                Row {
                    val cells = if (row.isEmpty()) listOf("") else row
                    cells.forEach { value ->
                        Surface(tonalElevation = if (rowIndex == 0) 2.dp else 0.dp, modifier = Modifier.width(150.dp).heightIn(min = 46.dp)) {
                            Text(value, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal)
                        }
                        VerticalDivider(Modifier.height(46.dp))
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PresentationView(slides: List<SlideData>, modifier: Modifier = Modifier) {
    if (slides.isEmpty()) {
        Box(modifier.fillMaxSize().padding(20.dp)) { Text("Cette présentation ne contient aucune diapositive lisible.") }
        return
    }
    var selected by remember { mutableIntStateOf(0) }
    val slide = slides[selected.coerceIn(slides.indices)]
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { if (selected > 0) selected-- }, enabled = selected > 0) { Text("‹ Précédente") }
            Text("${selected + 1} / ${slides.size}", modifier = Modifier.padding(top = 12.dp), fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { if (selected < slides.lastIndex) selected++ }, enabled = selected < slides.lastIndex) { Text("Suivante ›") }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(slide.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                HorizontalDivider()
                if (slide.text.isBlank()) Text("Cette diapositive ne contient pas de texte lisible.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text(slide.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun readOfficeDocument(file: File): OfficeContent {
    val ext = file.extension.lowercase()
    require(ext in setOf("docx", "odt", "xlsx", "ods", "pptx", "odp")) { "Format non pris en charge : .$ext" }
    return when (ext) {
        "xlsx" -> OfficeContent.Workbook(readXlsx(file))
        "ods" -> OfficeContent.Workbook(readOds(file))
        "pptx" -> OfficeContent.Presentation(readPptx(file))
        "odp" -> OfficeContent.Presentation(readOdp(file))
        else -> ZipFile(file).use { zip ->
            val entryName = if (ext == "docx") "word/document.xml" else "content.xml"
            val entry = zip.getEntry(entryName) ?: error("Contenu du document introuvable")
            zip.getInputStream(entry).use { input -> OfficeContent.Text(if (ext == "docx") extractDocxText(input) else extractOdtText(input)) }
        }
    }
}

private fun readPptx(file: File): List<SlideData> = ZipFile(file).use { zip ->
    val slideEntries = zip.entries().asSequence()
        .filter { !it.isDirectory && Regex("ppt/slides/slide\\d+\\.xml").matches(it.name) }
        .sortedBy { Regex("slide(\\d+)\\.xml").find(it.name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }
        .toList()
    slideEntries.mapIndexed { index, entry ->
        val text = zip.getInputStream(entry).use(::extractPptxSlideText)
        SlideData("Diapositive ${index + 1}", text)
    }
}

private fun extractPptxSlideText(input: InputStream): String {
    val parser = newParser(input); val out = StringBuilder(); var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        val tag = parser.name?.substringAfter(':')
        if (event == XmlPullParser.START_TAG && tag == "t") {
            val value = parser.nextText()
            if (value.isNotBlank()) {
                if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                out.append(value)
            }
        }
        event = parser.next()
    }
    return cleanDocumentText(out.toString())
}

private fun readOdp(file: File): List<SlideData> = ZipFile(file).use { zip ->
    val entry = zip.getEntry("content.xml") ?: error("Présentation ODP invalide")
    zip.getInputStream(entry).use(::readOdpContent)
}

private fun readOdpContent(input: InputStream): List<SlideData> {
    val parser = newParser(input); val slides = mutableListOf<SlideData>()
    var inPage = false; var pageName = ""; var out = StringBuilder(); var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        val tag = parser.name?.substringAfter(':')
        if (event == XmlPullParser.START_TAG) when (tag) {
            "page" -> { inPage = true; pageName = attrByLocalName(parser, "name") ?: "Diapositive ${slides.size + 1}"; out = StringBuilder() }
            "p", "h" -> if (inPage && out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
            "tab" -> if (inPage) out.append('\t')
            "line-break" -> if (inPage) out.append('\n')
        } else if (event == XmlPullParser.TEXT && inPage) out.append(parser.text)
        else if (event == XmlPullParser.END_TAG && tag == "page" && inPage) {
            slides += SlideData(pageName, cleanDocumentText(out.toString())); inPage = false
        }
        event = parser.next()
    }
    return slides
}

private fun readXlsx(file: File): List<SheetData> = ZipFile(file).use { zip ->
    val shared = zip.getEntry("xl/sharedStrings.xml")?.let { e -> zip.getInputStream(e).use(::readSharedStrings) }.orEmpty()
    val workbook = zip.getEntry("xl/workbook.xml") ?: error("Classeur XLSX invalide")
    val names = zip.getInputStream(workbook).use(::readSheetNames)
    names.mapIndexedNotNull { index, name ->
        val entry = zip.getEntry("xl/worksheets/sheet${index + 1}.xml") ?: return@mapIndexedNotNull null
        SheetData(name, zip.getInputStream(entry).use { readXlsxSheet(it, shared) })
    }
}

private fun readSharedStrings(input: InputStream): List<String> {
    val parser = newParser(input); val result = mutableListOf<String>(); var current: StringBuilder? = null
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        val tag = parser.name?.substringAfter(':')
        if (event == XmlPullParser.START_TAG && tag == "si") current = StringBuilder()
        else if (event == XmlPullParser.START_TAG && tag == "t") current?.append(parser.nextText())
        else if (event == XmlPullParser.END_TAG && tag == "si") { result += current?.toString().orEmpty(); current = null }
        event = parser.next()
    }
    return result
}

private fun readSheetNames(input: InputStream): List<String> {
    val parser = newParser(input); val names = mutableListOf<String>(); var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && parser.name.substringAfter(':') == "sheet") names += parser.getAttributeValue(null, "name") ?: "Feuille ${names.size + 1}"
        event = parser.next()
    }
    return names
}

private fun readXlsxSheet(input: InputStream, shared: List<String>): List<List<String>> {
    val parser = newParser(input); val rows = mutableListOf<MutableList<String>>(); var row = mutableListOf<String>()
    var cellType: String? = null; var cellRef: String? = null; var value = ""; var inline = ""; var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        val tag = parser.name?.substringAfter(':')
        if (event == XmlPullParser.START_TAG) when (tag) {
            "row" -> row = mutableListOf()
            "c" -> { cellType = parser.getAttributeValue(null, "t"); cellRef = parser.getAttributeValue(null, "r"); value = ""; inline = "" }
            "v" -> value = parser.nextText()
            "t" -> if (cellType == "inlineStr") inline += parser.nextText()
        } else if (event == XmlPullParser.END_TAG) when (tag) {
            "c" -> {
                val col = cellRef?.takeWhile { it.isLetter() }?.let(::excelColumnIndex) ?: row.size
                while (row.size < col) row += ""
                val shown = when (cellType) { "s" -> shared.getOrNull(value.toIntOrNull() ?: -1).orEmpty(); "b" -> if (value == "1") "VRAI" else "FAUX"; "inlineStr" -> inline; else -> value }
                if (row.size == col) row += shown else if (col < row.size) row[col] = shown
            }
            "row" -> rows += row
        }
        event = parser.next()
    }
    return trimTable(rows)
}

private fun excelColumnIndex(letters: String): Int {
    var n = 0; letters.uppercase().forEach { n = n * 26 + (it - 'A' + 1) }; return (n - 1).coerceAtLeast(0)
}

private fun readOds(file: File): List<SheetData> = ZipFile(file).use { zip ->
    val entry = zip.getEntry("content.xml") ?: error("Classeur ODS invalide")
    zip.getInputStream(entry).use(::readOdsContent)
}

private fun readOdsContent(input: InputStream): List<SheetData> {
    val parser = newParser(input); val sheets = mutableListOf<SheetData>(); var name = "Feuille 1"
    var rows = mutableListOf<MutableList<String>>(); var row = mutableListOf<String>(); var cellText = StringBuilder(); var inCell = false
    var cellRepeat = 1; var rowRepeat = 1; var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        val tag = parser.name?.substringAfter(':')
        if (event == XmlPullParser.START_TAG) when (tag) {
            "table" -> { name = attrByLocalName(parser, "name") ?: "Feuille ${sheets.size + 1}"; rows = mutableListOf() }
            "table-row" -> { row = mutableListOf(); rowRepeat = (attrByLocalName(parser, "number-rows-repeated")?.toIntOrNull() ?: 1).coerceAtMost(1000) }
            "table-cell", "covered-table-cell" -> { inCell = true; cellText = StringBuilder(); cellRepeat = (attrByLocalName(parser, "number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtMost(1000) }
            "p" -> if (inCell && cellText.isNotEmpty()) cellText.append('\n')
        } else if (event == XmlPullParser.TEXT && inCell) cellText.append(parser.text)
        else if (event == XmlPullParser.END_TAG) when (tag) {
            "table-cell", "covered-table-cell" -> { repeat(cellRepeat) { row += cellText.toString().trim() }; inCell = false }
            "table-row" -> repeat(rowRepeat) { rows += row.toMutableList() }
            "table" -> sheets += SheetData(name, trimTable(rows))
        }
        event = parser.next()
    }
    return sheets
}

private fun attrByLocalName(parser: XmlPullParser, local: String): String? {
    for (i in 0 until parser.attributeCount) if (parser.getAttributeName(i).substringAfter(':') == local) return parser.getAttributeValue(i)
    return null
}

private fun trimTable(rows: List<List<String>>): List<List<String>> {
    val lastRow = rows.indexOfLast { r -> r.any { it.isNotBlank() } }
    if (lastRow < 0) return emptyList()
    val useful = rows.take(lastRow + 1)
    val maxCol = useful.maxOfOrNull { r -> r.indexOfLast { it.isNotBlank() } + 1 } ?: 0
    return useful.map { it.take(maxCol) }
}

private fun extractDocxText(input: InputStream): String {
    val parser = newParser(input); val out = StringBuilder(); var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) when (parser.name.substringAfter(':')) { "t" -> out.append(parser.nextText()); "tab" -> out.append('\t'); "br", "cr" -> out.append('\n') }
        else if (event == XmlPullParser.END_TAG && parser.name.substringAfter(':') == "p") appendParagraphBreak(out)
        event = parser.next()
    }
    return cleanDocumentText(out.toString())
}

private fun extractOdtText(input: InputStream): String {
    val parser = newParser(input); val out = StringBuilder(); var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.TEXT) out.append(parser.text)
        if (event == XmlPullParser.START_TAG) when (parser.name.substringAfter(':')) { "tab" -> out.append('\t'); "line-break" -> out.append('\n') }
        else if (event == XmlPullParser.END_TAG) when (parser.name.substringAfter(':')) { "p", "h", "list-item" -> appendParagraphBreak(out) }
        event = parser.next()
    }
    return cleanDocumentText(out.toString())
}

private fun newParser(input: InputStream): XmlPullParser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser().apply { setInput(input, "UTF-8") }
private fun appendParagraphBreak(out: StringBuilder) { if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n') }
private fun cleanDocumentText(text: String): String = text.replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
