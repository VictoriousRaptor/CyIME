import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// What follows a whole word (2026-11-16), for the association bar. The corpus table
// (assets/association/lccc-base-v1.bin) keys on the last one or two characters, so after a longer word the
// suggestions followed its last character only (厄尔尼诺 gave 曼 and 斯 of 诺曼, 诺斯, never 现象). The dictionary
// already knows what follows such a word: a longer entry that begins with it (厄尔尼诺现象). For every entry
// D = P + X with P itself an entry of two to four characters and X of one to six, a row P -> X is written with
// D's weight as its count and P's own weight plus the weights of all entries beginning with P as the total: the
// same count/total = P(continuation | context) scale as the corpus table. Two-character words the corpus table
// has a row for are left to it. Read by WordContinuationTable.kt.
// Size: rows below wordAssociationMinWeight are dropped, at most wordAssociationTop a word. At 100 and 4 the
// pinned dictionaries give about 116,000 words, 212,000 rows, 1.9 MB compressed (50: 170,000 words, 3.0 MB).
val wordAssociationRoot = layout.buildDirectory.dir("generated/word-association-assets/association")
val wordAssociationMinWeight = 100
val wordAssociationTop = 4

val prepareWordAssociations by tasks.registering {
    group = "build setup"
    description = "Write what follows whole words (association bar) from the bundled Chinese dictionaries"
    dependsOn("prepareChineseDictionaries")
    val dictionaries = layout.buildDirectory.dir("generated/chinese-assets/rime_ice/cn_dicts")
    val baseTable = file("src/main/assets/association/lccc-base-v1.bin")
    inputs.dir(dictionaries)
    inputs.file(baseTable)
    inputs.property("wordContinuations", 1)
    inputs.property("minWeight", wordAssociationMinWeight)
    inputs.property("top", wordAssociationTop)
    outputs.dir(wordAssociationRoot)
    doLast {
        fun han(text: String) = text.isNotEmpty() && text.all { it in '一'..'鿿' }
        val weights = HashMap<String, Int>(1 shl 21)
        for (name in listOf("base", "ext", "tencent", "others")) {
            var body = false
            File(dictionaries.get().asFile, "$name.dict.yaml").forEachLine { line ->
                if (!body) { body = line.trim() == "..."; return@forEachLine }
                if (line.startsWith("#") || line.isBlank()) return@forEachLine
                val fields = line.split('\t')
                if (!han(fields[0])) return@forEachLine
                // Columns: text, code, weight (base, ext); text, weight (tencent); text and maybe a code (others).
                val last = fields.last()
                val weight = if (fields.size > 1 && last.length in 1..9 && last.all { it in '0'..'9' }) last.toInt() else 0
                if (weight > (weights[fields[0]] ?: -1)) weights[fields[0]] = weight
            }
        }
        val known = HashSet<String>()
        GZIPInputStream(baseTable.inputStream()).bufferedReader().useLines { lines ->
            lines.filterNot { it.startsWith("#") || it.isBlank() }.forEach { known.add(it.substringBefore('\t')) }
        }
        val rows = HashMap<String, MutableList<Pair<Int, String>>>()
        val totals = HashMap<String, Long>()
        for ((word, weight) in weights) {
            val length = word.length
            if (length < 3) continue
            for (size in 2..minOf(4, length - 1)) {
                if (length - size > 6) continue
                val head = word.substring(0, size)
                if (head !in weights || (size == 2 && head in known)) continue
                totals[head] = (totals[head] ?: 0L) + weight
                if (weight >= wordAssociationMinWeight) rows.getOrPut(head) { mutableListOf() }.add(weight to word.substring(size))
            }
        }
        val text = StringBuilder("# cyime-dict-word-continuations-v1\n")
        var candidates = 0
        for (head in rows.keys.sorted()) {
            val total = weights.getValue(head) + totals.getValue(head)
            val best = rows.getValue(head).sortedWith(compareBy<Pair<Int, String>>({ -it.first }, { it.second }))
            for ((weight, rest) in best.take(wordAssociationTop)) {
                text.append(head).append('\t').append(rest).append('\t').append(weight).append('\t').append(total).append('\n')
                candidates++
            }
        }
        // A changed dictionary layout (weights no longer read) would leave this nearly empty or several times larger.
        check(rows.size in 50_000..250_000 && candidates <= 500_000) { "Word continuations: ${rows.size} words, $candidates rows" }
        check(text.contains("\n厄尔尼诺\t现象\t")) { "Word continuations: 厄尔尼诺 -> 现象 is missing" }
        val output = File(wordAssociationRoot.get().asFile.apply { mkdirs() }, "dict-words-v1.gz")
        GZIPOutputStream(output.outputStream()).use { it.write(text.toString().toByteArray(Charsets.UTF_8)) }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareWordAssociations) }
