package com.evanaronson.linguize.core.morphology

/**
 * Lab: Catalan verb paradigms (central standard), generated. Regular -ar, -re, -er and -ir
 * verbs follow the rules below: -ir verbs are inchoative (serveixo) unless the lexicon marks
 * them pure (":p", dormo) or pure with o becoming u when stressed (":u", surto). Spelling
 * changes before e and i (toquem, pagui, comenci, mengem) and the diaeresis after a vowel
 * (estudiï, conduïm) are applied. The most common irregular verbs are written out, many of
 * them as "velar" verbs (prenc, prengui, prengués). The periphrastic past auxiliaries that are
 * not also anar's present (vam, vau, vares…) are listed under [Tense.PeriphrasticPast].
 */
internal object Catalan {
    data class Spec(val lemma: String, val kind: Char? = null, val participle: String? = null, val base: String? = null)

    fun parse(entry: String): Spec {
        if ('=' in entry && !entry.contains(":p=")) {
            val (lemma, base) = entry.split('=', limit = 2)
            return Spec(lemma, base = base)
        }
        val parts = entry.split(':')
        var kind: Char? = null
        var participle: String? = null
        parts.drop(1).forEach { part ->
            if (part.startsWith("p=")) participle = part.removePrefix("p=") else kind = part.first()
        }
        return Spec(parts[0], kind, participle)
    }

    val lexicon: List<Spec> by lazy { CATALAN_VERBS.trim().split(Regex("\\s+")).map(::parse).distinctBy { it.lemma } }

    fun conjugate(spec: Spec): List<VerbForm> {
        spec.base?.let { base ->
            val prefix = spec.lemma.removeSuffix(base)
            val baseSpec = lexicon.firstOrNull { it.lemma == base } ?: Spec(base)
            return conjugate(baseSpec).map { it.copy(form = prefix + it.form) }
        }
        return Paradigm(spec).forms()
    }

    /** Periphrastic past auxiliaries and their persons; vaig, vas, va and van are also anar's present. */
    val PERIPHRASTIC: Map<String, Person> = mapOf(
        "vaig" to Person.S1, "vas" to Person.S2, "vares" to Person.S2, "va" to Person.S3,
        "vam" to Person.P1, "vàrem" to Person.P1, "vau" to Person.P2, "vàreu" to Person.P2,
        "van" to Person.P3, "varen" to Person.P3,
    )

    private class Irregular(
        val pres: String? = null,
        val impf: String? = null,
        /** Stem for the imperfect (and, by default, the gerund) with -re/-er endings: pren- gives prenia. */
        val impfStem: String? = null,
        val fut: String? = null,
        val subj: String? = null,
        /** Velar subjunctive stem: pugu- gives pugui, puguem. */
        val subjStem: String? = null,
        /** Velar past stem, for the simple past and imperfect subjunctive: pogu- gives poguí, pogués. Defaults to [subjStem]. */
        val pastStem: String? = null,
        val isubj: String? = null,
        val past: String? = null,
        val ger: String? = null,
        /** One participle, or several separated by "|". */
        val part: String? = null,
        /** Affirmative tu imperative; "-" when the verb has none. */
        val imp: String? = null,
        val extra: List<VerbForm> = emptyList(),
    )

    /** prendre-like verbs: prenc, prens, pren, prenem; prengui; prengués. */
    private fun velar(stem: String, third: String, part: String, fut: String? = null) = Irregular(
        pres = "${stem}c ${stem}s $third ${stem}em ${stem}eu ${stem}en",
        impfStem = stem, subjStem = stem + "gu", part = part, imp = third, fut = fut,
    )

    private val IRREGULAR: Map<String, Irregular> = mapOf(
        "ser" to Irregular(
            pres = "sóc ets és som sou són", impf = "era eres era érem éreu eren", fut = "ser",
            subj = "sigui siguis sigui siguem sigueu siguin", isubj = "fos fossis fos fóssim fóssiu fossin",
            past = "fui fores fou fórem fóreu foren", ger = "sent", part = "sigut|estat", imp = "sigues",
            // soc, ets, es: the 2017 spelling drops the accent on sóc.
            extra = listOf(VerbForm("ésser", Tense.Infinitive, null), VerbForm("essent", Tense.Gerund, null), VerbForm("soc", Tense.Present, Person.S1)),
        ),
        "estar" to Irregular(pres = "estic estàs està estem esteu estan", subjStem = "estigu", imp = "estigues"),
        "anar" to Irregular(
            pres = "vaig vas va anem aneu van", fut = "anir", subj = "vagi vagis vagi anem aneu vagin", imp = "vés",
            extra = listOf(
                VerbForm("vares", Tense.PeriphrasticPast, Person.S2), VerbForm("vam", Tense.PeriphrasticPast, Person.P1),
                VerbForm("vàrem", Tense.PeriphrasticPast, Person.P1), VerbForm("vau", Tense.PeriphrasticPast, Person.P2),
                VerbForm("vàreu", Tense.PeriphrasticPast, Person.P2), VerbForm("varen", Tense.PeriphrasticPast, Person.P3),
                VerbForm("ves", Tense.Imperative, Person.S2),
            ),
        ),
        "haver" to Irregular(
            pres = "he has ha hem heu han", fut = "haur", subj = "hagi hagis hagi hàgim hàgiu hagin",
            pastStem = "hagu", part = "hagut", imp = "-",
            extra = listOf(
                VerbForm("havem", Tense.Present, Person.P1), VerbForm("haveu", Tense.Present, Person.P2),
                VerbForm("haig", Tense.Present, Person.S1),
            ),
        ),
        "tenir" to Irregular(pres = "tinc tens té tenim teniu tenen", fut = "tindr", subjStem = "tingu", part = "tingut", imp = "tingues"),
        "fer" to Irregular(
            pres = "faig fas fa fem feu fan", impf = "feia feies feia fèiem fèieu feien", fut = "far",
            subj = "faci facis faci fem feu facin", isubj = "fes fessis fes féssim féssiu fessin",
            past = "fiu feres féu férem féreu feren", ger = "fent", part = "fet", imp = "fes",
        ),
        "poder" to Irregular(pres = "puc pots pot podem podeu poden", fut = "podr", subjStem = "pugu", pastStem = "pogu", part = "pogut", imp = "-"),
        "dir" to Irregular(
            pres = "dic dius diu diem dieu diuen", impf = "deia deies deia dèiem dèieu deien", fut = "dir",
            subjStem = "digu", ger = "dient", part = "dit", imp = "digues",
        ),
        "veure" to Irregular(
            pres = "veig veus veu veiem veieu veuen", impf = "veia veies veia vèiem vèieu veien", fut = "veur",
            subj = "vegi vegis vegi vegem vegeu vegin", isubj = "veiés veiessis veiés veiéssim veiéssiu veiessin",
            past = "viu veieres veié veiérem veiéreu veieren", ger = "veient", part = "vist", imp = "veges",
        ),
        "donar" to Irregular(extra = listOf(VerbForm("dóna", Tense.Present, Person.S3), VerbForm("dóna", Tense.Imperative, Person.S2))),
        "saber" to Irregular(
            pres = "sé saps sap sabem sabeu saben", fut = "sabr",
            subj = "sàpiga sàpigues sàpiga sapiguem sapigueu sàpiguen", imp = "sàpigues",
        ),
        "voler" to Irregular(pres = "vull vols vol volem voleu volen", fut = "voldr", subjStem = "vulgu", pastStem = "volgu", part = "volgut", imp = "vulgues"),
        "venir" to Irregular(
            pres = "vinc vens ve venim veniu venen", fut = "vindr", subjStem = "vingu", part = "vingut", imp = "vine",
            extra = listOf(VerbForm("vénen", Tense.Present, Person.P3)),
        ),
        "viure" to Irregular(pres = "visc vius viu vivim viviu viuen", impfStem = "viv", subjStem = "visqu", ger = "vivint", part = "viscut", imp = "viu"),
        "escriure" to Irregular(
            pres = "escric escrius escriu escrivim escriviu escriuen", impfStem = "escriv", subjStem = "escrigu",
            ger = "escrivint", part = "escrit", imp = "escriu",
        ),
        "beure" to Irregular(pres = "bec beus beu bevem beveu beuen", impfStem = "bev", subjStem = "begu", part = "begut", imp = "beu"),
        "conèixer" to Irregular(
            pres = "conec coneixes coneix coneixem coneixeu coneixen", impfStem = "coneix", fut = "coneixer",
            subjStem = "conegu", part = "conegut", imp = "coneix",
        ),
        "aparèixer" to Irregular(
            pres = "aparec apareixes apareix apareixem apareixeu apareixen", impfStem = "apareix", fut = "apareixer",
            subjStem = "aparegu", part = "aparegut", imp = "apareix",
        ),
        "prendre" to velar("pren", "pren", "pres"),
        "entendre" to velar("enten", "entén", "entès"),
        "atendre" to velar("aten", "atén", "atès"),
        "estendre" to velar("esten", "estén", "estès"),
        "pretendre" to velar("preten", "pretén", "pretès"),
        "encendre" to velar("encen", "encén", "encès"),
        "dependre" to velar("depen", "depèn", "depès"),
        "ofendre" to velar("ofen", "ofèn", "ofès"),
        "suspendre" to velar("suspen", "suspèn", "suspès"),
        "vendre" to velar("ven", "ven", "venut"),
        "fondre" to velar("fon", "fon", "fos"),
        "confondre" to velar("confon", "confon", "confós"),
        "respondre" to velar("respon", "respon", "respost"),
        "correspondre" to velar("correspon", "correspon", "correspost"),
        "resoldre" to velar("resol", "resol", "resolt"),
        "valer" to velar("val", "val", "valgut", fut = "valdr"),
        "treure" to Irregular(
            pres = "trec treus treu traiem traieu treuen", impf = "treia treies treia trèiem trèieu treien", fut = "traur",
            subjStem = "tregu", ger = "traient", part = "tret", imp = "treu",
        ),
        "creure" to Irregular(
            pres = "crec creus creu creiem creieu creuen", impf = "creia creies creia crèiem crèieu creien", fut = "creur",
            subjStem = "cregu", ger = "creient", part = "cregut", imp = "creu",
        ),
        "caure" to Irregular(
            pres = "caic caus cau caiem caieu cauen", impf = "queia queies queia quèiem quèieu queien", fut = "caur",
            subjStem = "caigu", ger = "caient", part = "caigut", imp = "cau",
        ),
        "riure" to Irregular(
            pres = "ric rius riu riem rieu riuen", impf = "reia reies reia rèiem rèieu reien", fut = "riur",
            subjStem = "rigu", ger = "rient", part = "rigut", imp = "riu",
        ),
        "seure" to Irregular(
            pres = "sec seus seu seiem seieu seuen", impf = "seia seies seia sèiem sèieu seien", fut = "seur",
            subjStem = "segu", ger = "seient", part = "segut", imp = "seu",
        ),
        "moure" to Irregular(pres = "moc mous mou movem moveu mouen", impfStem = "mov", fut = "mour", subjStem = "mogu", part = "mogut", imp = "mou"),
        "dur" to Irregular(
            pres = "duc duus duu duem dueu duen", impf = "duia duies duia dúiem dúieu duien", fut = "dur",
            subjStem = "dugu", ger = "duent", part = "dut", imp = "duu",
        ),
        "córrer" to Irregular(
            pres = "corro corres corre correm correu corren", impfStem = "corr", fut = "correr",
            subj = "corri corris corri correm correu corrin", pastStem = "corregu", ger = "corrent", part = "corregut", imp = "corre",
        ),
        "néixer" to Irregular(
            pres = "neixo neixes neix neixem neixeu neixen", impfStem = "neix", fut = "naixer",
            subj = "neixi neixis neixi neixem neixeu neixin", pastStem = "nasqu", ger = "naixent", part = "nascut", imp = "neix",
        ),
        "créixer" to Irregular(
            pres = "creixo creixes creix creixem creixeu creixen", impfStem = "creix", fut = "creixer",
            subj = "creixi creixis creixi creixem creixeu creixin", pastStem = "cresqu", ger = "creixent", part = "crescut", imp = "creix",
        ),
        "caldre" to Irregular(
            pres = "- - cal - - -", impf = "- - calia - - -", fut = "caldr", subj = "- - calgui - - -",
            isubj = "- - calgués - - -", past = "- - calgué - - -", ger = "calent", part = "calgut", imp = "-",
        ),
        "deure" to Irregular(pres = "dec deus deu devem deveu deuen", impfStem = "dev", subjStem = "degu", part = "degut", imp = "-"),
        "plaure" to Irregular(pres = "plac plaus plau plaem plaeu plauen", impf = "plaïa plaïes plaïa plaíem plaíeu plaïen", subjStem = "plagu", ger = "plaent", part = "plagut", imp = "-"),
        "fugir" to Irregular(pres = "fujo fuges fuig fugim fugiu fugen", subj = "fugi fugis fugi fugim fugiu fugin", imp = "fuig"),
    )

    private class Paradigm(spec: Spec) {
        val lemma = spec.lemma
        val irregular = IRREGULAR[lemma]
        val kind = spec.kind
        val participle = spec.participle

        /** 'a' -ar, 'r' -re, 'e' -er, 'i' inchoative -ir, 'p' pure -ir. */
        val cls: Char = when {
            lemma.endsWith("ar") -> 'a'
            lemma.endsWith("re") -> 'r'
            lemma.endsWith("er") -> 'e'
            kind == 'p' || kind == 'u' -> 'p'
            else -> 'i'
        }
        val stem = stripAccents(lemma.dropLast(2))
        val strongStem = if (kind == 'u') replaceLast(stem, 'o', "u") else stem

        fun join(stem: String, ending: String): String {
            // obrir, omplir: obro, obres, obre.
            if (cls == 'p' && (ending == "s" || ending.isEmpty()) && stem.length > 2 && stem.last() in "rl" &&
                !stem[stem.length - 2].isVowel() && !stem.endsWith("ll")
            ) return stem + "e" + ending
            if (ending.isEmpty()) return finalStem(stem)
            val first = ending[0]
            if (cls == 'a' && first in "eéiíï") {
                val changed = when {
                    stem.endsWith("gu") -> stem.dropLast(2) + "gü"
                    stem.endsWith("qu") -> stem.dropLast(2) + "qü"
                    stem.endsWith("c") -> stem.dropLast(1) + "qu"
                    stem.endsWith("g") -> stem + "u"
                    stem.endsWith("ç") -> stem.dropLast(1) + "c"
                    stem.endsWith("j") -> stem.dropLast(1) + "g"
                    else -> stem
                }
                return changed + diaeresis(changed, ending)
            }
            if (ending == "s" && cls != 'a') {
                return if (SIBILANT.any { stem.endsWith(it) }) stem + "es" else finalStem(stem) + "s"
            }
            return stem + diaeresis(stem, ending)
        }

        /** b is written p at the end of a word: rebre, rep. */
        fun finalStem(stem: String) = if (stem.endsWith("b")) stem.dropLast(1) + "p" else stem

        fun diaeresis(stem: String, ending: String): String =
            if (stem.isNotEmpty() && stem.last() in "aeiou" && !stem.endsWith("gu") && !stem.endsWith("qu") &&
                ending.startsWith("i") && ending != "int"
            ) "ï" + ending.substring(1) else ending

        fun forms(): List<VerbForm> {
            val out = mutableListOf<VerbForm>()
            val irr = irregular
            out += VerbForm(lemma, Tense.Infinitive, null)

            val present = irr?.pres?.let(::row) ?: when (cls) {
                'a' -> listOf("o", "es", "a", "em", "eu", "en")
                'r', 'e' -> listOf("o", "s", "", "em", "eu", "en")
                'p' -> listOf("o", "s", "", "im", "iu", "en")
                else -> listOf("eixo", "eixes", "eix", "im", "iu", "eixen")
            }.mapIndexed { index, ending -> join(if (index in STRESSED) strongStem else stem, ending) }
            out.addRow(Tense.Present, present)

            val iaEndings = listOf("ia", "ies", "ia", "íem", "íeu", "ien")
            val imperfect = irr?.impf?.let(::row)
                ?: irr?.impfStem?.let { s -> iaEndings.map { s + it } }
                ?: if (cls == 'a') listOf("ava", "aves", "ava", "àvem", "àveu", "aven").map { join(stem, it) }
                else iaEndings.map { join(stem, it) }
            out.addRow(Tense.Imperfect, imperfect)

            val future = irr?.fut ?: if (cls == 'r') stripAccents(lemma.dropLast(1)) else stripAccents(lemma)
            out.addRow(Tense.Future, listOf("é", "às", "à", "em", "eu", "an").map { future + it })
            out.addRow(Tense.Conditional, iaEndings.map { future + it })

            val subjunctive = irr?.subj?.let(::row)
                ?: irr?.subjStem?.let { s -> listOf("i", "is", "i", "em", "eu", "in").map { s + it } }
                ?: when (cls) {
                    'a', 'r', 'e' -> listOf("i", "is", "i", "em", "eu", "in")
                    'p' -> listOf("i", "is", "i", "im", "iu", "in")
                    else -> listOf("eixi", "eixis", "eixi", "im", "iu", "eixin")
                }.mapIndexed { index, ending -> join(if (index in STRESSED) strongStem else stem, ending) }
            out.addRow(Tense.PresentSubjunctive, subjunctive)

            val pastStem = irr?.pastStem ?: irr?.subjStem
            val imperfectSubjunctive = irr?.isubj?.let(::row)
                ?: pastStem?.let { s -> listOf("és", "essis", "és", "éssim", "éssiu", "essin").map { s + it } }
                ?: when (cls) {
                    'a', 'r', 'e' -> listOf("és", "essis", "és", "éssim", "éssiu", "essin")
                    else -> listOf("ís", "issis", "ís", "íssim", "íssiu", "issin")
                }.map { join(stem, it) }
            out.addRow(Tense.ImperfectSubjunctive, imperfectSubjunctive)

            val simplePast = irr?.past?.let(::row)
                ?: pastStem?.let { s -> listOf("í", "eres", "é", "érem", "éreu", "eren").map { s + it } }
                ?: when (cls) {
                    'a' -> listOf("í", "ares", "à", "àrem", "àreu", "aren")
                    'r', 'e' -> listOf("í", "eres", "é", "érem", "éreu", "eren")
                    else -> listOf("í", "ires", "í", "írem", "íreu", "iren")
                }.map { join(stem, it) }
            out.addRow(Tense.Preterite, simplePast)

            val tu = irr?.imp ?: present[2]
            if (tu != null && tu != "-") out += VerbForm(tu, Tense.Imperative, Person.S2)
            // sigues, tingues, digues borrow the subjunctive: sigueu, tingueu, digueu.
            val vosaltres = if (tu != null && tu != present[2] && tu.endsWith("es")) subjunctive[4] else present[4]
            if (irr?.imp != "-") vosaltres?.let { out += VerbForm(it, Tense.Imperative, Person.P2) }

            val gerund = irr?.ger ?: irr?.impfStem?.let { it + if (cls == 'p' || cls == 'i') "int" else "ent" } ?: when (cls) {
                'a' -> stem + "ant"
                'r', 'e' -> stem + "ent"
                else -> stem + "int"
            }
            out += VerbForm(gerund, Tense.Gerund, null)

            val written = irr?.part ?: participle
            if (written != null) {
                written.split('|').forEach { part -> participleForms(part, regular = false).forEach { out += VerbForm(it, Tense.Participle, null) } }
            } else {
                val part = join(stem, if (cls == 'a') "at" else if (cls == 'r' || cls == 'e') "ut" else "it")
                participleForms(part, regular = true).forEach { out += VerbForm(it, Tense.Participle, null) }
            }
            irr?.extra?.let { out += it }
            return out
        }
    }

    /** cantat, cantada, cantats, cantades; entès, entesa, entesos, enteses; fet, feta, fets, fetes. */
    fun participleForms(part: String, regular: Boolean): List<String> {
        val plain = stripAccents(part)
        return when {
            part.endsWith("s") -> listOf(part, plain + "a", plain + "os", plain + "es")
            part.endsWith("st") -> listOf(part, part + "a", part + "os", part + "s", part + "es")
            (regular || part.endsWith("ut") || part.endsWith("at")) && part.length > 3 -> {
                val root = part.dropLast(1)
                listOf(part, root + "da", part + "s", root + "des")
            }
            part.endsWith("t") -> listOf(part, part + "a", part + "s", part + "es")
            else -> listOf(part)
        }
    }

    private val STRESSED = setOf(0, 1, 2, 5)
    private val SIBILANT = listOf("s", "x", "ç", "tx", "ig")

    private fun replaceLast(stem: String, vowel: Char, with: String): String {
        val index = stem.lastIndexOf(vowel)
        return if (index < 0) stem else stem.substring(0, index) + with + stem.substring(index + 1)
    }
}

/** A couple of hundred common Catalan verbs. ":p" pure -ir, ":u" pure -ir with o→u, "p=" an irregular participle, "=" a prefixed irregular. */
private const val CATALAN_VERBS = """
ser estar anar haver tenir fer poder dir veure donar saber voler venir posar sortir:u viure escriure beure conèixer
aparèixer prendre entendre atendre estendre pretendre encendre dependre ofendre suspendre vendre fondre confondre respondre
correspondre resoldre valer treure creure caure riure seure moure dur córrer néixer créixer caldre fugir
parlar treballar estudiar mirar escoltar esperar buscar necessitar usar ajudar acabar començar entrar canviar preguntar
contestar comprar pagar cuinar sopar dinar esmorzar viatjar visitar caminar ballar cantar nedar tocar guanyar gastar
estalviar llogar enviar explicar comptar trobar recordar oblidar pensar tancar despertar aixecar llevar dutxar rentar
netejar preparar arreglar convidar celebrar organitzar practicar aprovar provar mostrar costar sonar somiar volar jugar
intentar tractar parar continuar quedar deixar portar passar agafar tirar pujar baixar arribar tornar estimar odiar
agradar encantar importar molestar interessar preocupar faltar sobrar bastar tardar durar funcionar nevar plorar cridar
saludar besar abraçar casar separar mudar acompanyar apagar apuntar copiar esborrar gravar descansar aprofitar regalar
prestar cuidar crear dubtar votar xerrar avançar llançar creuar resar comunicar indicar publicar aplicar dedicar
equivocar desitjar passejar emprar millorar empitjorar augmentar notar fixar enganxar carregar descarregar navegar
arrencar aterrar fumar menjar trucar telefonar avisar reservar cancel·lar confirmar signar pintar dibuixar demanar
manar ensenyar semblar marxar cobrar triar utilitzar lluitar fregar planxar xatejar escriure acceptar rebutjar
presentar pujar aparcar conduir:i alegrar enfadar cansar animar opinar participar ocupar respectar
suportar aguantar matar salvar amagar apropar allunyar queixar relaxar entregar comprovar demostrar actualitzar
realitzar analitzar confiar guiar variar buidar refredar provar assajar recolzar plantejar picar mullar assecar sumar
restar ordenar juntar entrenar significar suposar perdonar callar disparar robar aturar escapar trencar vigilar imaginar
jurar resultar girar acostar adonar ficar anomenar instal·lar cremar abandonar evitar recuperar tallar esbrinar assassinar
atacar lligar arrestar investigar informar controlar acusar revisar conservar guardar obligar considerar encarregar
enamorar divorciar saltar colpejar rescatar despullar banyar pentinar afaitar gitar observar cercar tastar olorar fallar
fracassar comentar espatllar amenaçar mentir negar casar canviar odiar portar matar comptar sumar contar mirar
explicar acabar quedar deixar provar intentar estimar
seguir perseguir protegir convertir suggerir ferir competir imprimir adormir:p omplir:p:p=omplert
deure plaure
llegir decidir servir preferir patir compartir aconseguir oferir:i:p=ofert sofrir:i:p=sofert complir:i:p=complert
construir destruir traduir produir reduir unir reunir repartir dividir existir insistir assistir discutir
gaudir mentir permetre=metre prometre=metre admetre=metre cometre=metre metre:p=mès obeir agrair avorrir afegir
corregir elegir escopir:u cosir:u collir:u acollir:u recollir:u escollir:u tossir:u bullir:p morir:p:p=mort
sentir:p consentir:p partir:p vestir:p dormir:p obrir:p:p=obert cobrir:p:p=cobert descobrir:p:p=descobert
témer batre combatre debatre abatre rebre percebre concebre perdre fotre
obtenir=tenir mantenir=tenir contenir=tenir retenir=tenir sostenir=tenir detenir=tenir entretenir=tenir
prevenir=venir convenir=venir intervenir=venir esdevenir=venir desfer=fer refer=fer satisfer=fer predir=dir
contradir=dir aprendre=prendre comprendre=prendre sorprendre=prendre emprendre=prendre reprendre=prendre
descriure=escriure inscriure=escriure sobreviure=viure conviure=viure atreure=treure distreure=treure
extreure=treure commoure=moure promoure=moure remoure=moure somriure=riure reconèixer=conèixer
desconèixer=conèixer desaparèixer=aparèixer preveure=veure
"""
