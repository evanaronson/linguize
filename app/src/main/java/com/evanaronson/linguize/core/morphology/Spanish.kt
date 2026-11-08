package com.evanaronson.linguize.core.morphology

/**
 * Lab: Spanish verb paradigms, generated. Regular -ar/-er/-ir verbs follow the rules below,
 * with spelling changes (busqué, cojo, sigo, empecé), stem changes marked in the lexicon
 * (ie, ue, i, and the written accents í and ú of envío and continúo), -cer/-cir verbs with zc,
 * -uir verbs with y, -ducir preterites in j, and vowel stems (leyó, leído). The most common
 * irregular verbs are written out where they differ; prefixed verbs (obtener, proponer) are
 * conjugated as their base.
 */
internal object Spanish {
    /** One lexicon entry: "pensar:ie", "volver:ue:p=vuelto", "obtener=tener". */
    data class Spec(val lemma: String, val change: String? = null, val participle: String? = null, val base: String? = null)

    fun parse(entry: String): Spec {
        if ('=' in entry && !entry.contains(":p=")) {
            val (lemma, base) = entry.split('=', limit = 2)
            return Spec(lemma, base = base)
        }
        val parts = entry.split(':')
        var change: String? = null
        var participle: String? = null
        parts.drop(1).forEach { part ->
            if (part.startsWith("p=")) participle = part.removePrefix("p=") else change = part
        }
        return Spec(parts[0], change, participle)
    }

    val lexicon: List<Spec> by lazy { SPANISH_VERBS.trim().split(Regex("\\s+")).map(::parse).distinctBy { it.lemma } }

    fun conjugate(spec: Spec): List<VerbForm> {
        spec.base?.let { base ->
            val prefix = spec.lemma.removeSuffix(base)
            val baseSpec = lexicon.firstOrNull { it.lemma == base } ?: Spec(base)
            return conjugate(baseSpec).map { form ->
                val joined = prefix + form.form
                // ten, pon, ven become obtén, propón, prevén.
                val accented = if (form.tense == Tense.Imperative && form.person == Person.S2 &&
                    form.form.length <= 3 && form.form.endsWith("n") && form.form.count { it.isVowel() } == 1
                ) prefix + accentLastVowel(form.form) else joined
                form.copy(form = accented)
            }
        }
        return Paradigm(spec).forms()
    }

    private class Irregular(
        val pres: String? = null,
        val pret: String? = null,
        /** A strong preterite stem: tuv- gives tuve, tuviste, tuvo… */
        val strong: String? = null,
        val impf: String? = null,
        val fut: String? = null,
        val subj: String? = null,
        /** Affirmative tú imperative; "-" when the verb has none. */
        val imp: String? = null,
        val ger: String? = null,
        val part: String? = null,
        val extra: List<VerbForm> = emptyList(),
    )

    private val IRREGULAR: Map<String, Irregular> = mapOf(
        "ser" to Irregular(
            pres = "soy eres es somos sois son", pret = "fui fuiste fue fuimos fuisteis fueron",
            impf = "era eras era éramos erais eran", subj = "sea seas sea seamos seáis sean",
            imp = "sé", ger = "siendo", part = "sido",
        ),
        "estar" to Irregular(
            pres = "estoy estás está estamos estáis están", strong = "estuv",
            subj = "esté estés esté estemos estéis estén", imp = "está",
        ),
        "ir" to Irregular(
            pres = "voy vas va vamos vais van", pret = "fui fuiste fue fuimos fuisteis fueron",
            impf = "iba ibas iba íbamos ibais iban", subj = "vaya vayas vaya vayamos vayáis vayan",
            imp = "ve", ger = "yendo", part = "ido",
            extra = listOf(VerbForm("vamos", Tense.Imperative, Person.P1)),
        ),
        "haber" to Irregular(
            pres = "he has ha hemos habéis han", strong = "hub", fut = "habr",
            subj = "haya hayas haya hayamos hayáis hayan", imp = "-",
            extra = listOf(VerbForm("hay", Tense.Present, Person.S3)),
        ),
        "tener" to Irregular(pres = "tengo tienes tiene tenemos tenéis tienen", strong = "tuv", fut = "tendr", imp = "ten"),
        "hacer" to Irregular(
            pres = "hago haces hace hacemos hacéis hacen", pret = "hice hiciste hizo hicimos hicisteis hicieron",
            fut = "har", imp = "haz", part = "hecho",
        ),
        "poder" to Irregular(pres = "puedo puedes puede podemos podéis pueden", strong = "pud", fut = "podr", imp = "-", ger = "pudiendo"),
        "decir" to Irregular(pres = "digo dices dice decimos decís dicen", strong = "dij", fut = "dir", imp = "di", ger = "diciendo", part = "dicho"),
        "ver" to Irregular(
            pres = "veo ves ve vemos veis ven", pret = "vi viste vio vimos visteis vieron",
            impf = "veía veías veía veíamos veíais veían", part = "visto",
        ),
        "dar" to Irregular(pres = "doy das da damos dais dan", pret = "di diste dio dimos disteis dieron", subj = "dé des dé demos deis den"),
        "saber" to Irregular(pres = "sé sabes sabe sabemos sabéis saben", strong = "sup", fut = "sabr", subj = "sepa sepas sepa sepamos sepáis sepan"),
        "querer" to Irregular(pres = "quiero quieres quiere queremos queréis quieren", strong = "quis", fut = "querr"),
        "venir" to Irregular(pres = "vengo vienes viene venimos venís vienen", strong = "vin", fut = "vendr", imp = "ven", ger = "viniendo"),
        "poner" to Irregular(pres = "pongo pones pone ponemos ponéis ponen", strong = "pus", fut = "pondr", imp = "pon", part = "puesto"),
        "salir" to Irregular(pres = "salgo sales sale salimos salís salen", fut = "saldr", imp = "sal"),
        "traer" to Irregular(pres = "traigo traes trae traemos traéis traen", strong = "traj"),
        "caer" to Irregular(pres = "caigo caes cae caemos caéis caen"),
        "oír" to Irregular(pres = "oigo oyes oye oímos oís oyen", fut = "oir", imp = "oye"),
        "andar" to Irregular(strong = "anduv"),
        "caber" to Irregular(pres = "quepo cabes cabe cabemos cabéis caben", strong = "cup", fut = "cabr", imp = "-"),
        "valer" to Irregular(pres = "valgo vales vale valemos valéis valen", fut = "valdr"),
        "reír" to Irregular(
            pres = "río ríes ríe reímos reís ríen", pret = "reí reíste rió reímos reísteis rieron",
            fut = "reir", subj = "ría rías ría riamos riais rían", imp = "ríe", ger = "riendo", part = "reído",
        ),
        "soler" to Irregular(imp = "-"),
    )

    private class Paradigm(spec: Spec) {
        val lemma = spec.lemma
        val irregular = IRREGULAR[lemma]
        val change = spec.change
        val participle = spec.participle
        val cls: Char = when {
            lemma.endsWith("ar") -> 'a'
            lemma.endsWith("er") -> 'e'
            else -> 'i'
        }
        val stem = lemma.dropLast(2)
        val strongStem = strong(stem)
        val weakStem = if (cls == 'i') when (change) {
            "ie", "i" -> replaceLast(stem, 'e', "i")
            "ue" -> replaceLast(stem, 'o', "u")
            else -> stem
        } else stem
        val uir = cls == 'i' && stem.endsWith("u") && !stem.endsWith("gu") && !stem.endsWith("qu")
        val zc = (lemma.endsWith("cer") || lemma.endsWith("cir")) && stem.length >= 2 &&
            stem[stem.length - 2].isVowel() && lemma !in NO_ZC

        fun strong(stem: String): String = when (change) {
            "ie" -> replaceLast(stem, 'e', "ie")
            "ue" -> if ('o' in stem) replaceLast(stem, 'o', "ue") else replaceLast(stem, 'u', "ue")
            "i" -> replaceLast(stem, 'e', "i")
            "í" -> replaceLast(stem, 'i', "í")
            "ú" -> replaceLast(stem, 'u', "ú")
            else -> if (cls == 'i' && stem.endsWith("u") && !stem.endsWith("gu") && !stem.endsWith("qu")) stem + "y" else stem
        }

        fun join(stem: String, ending: String): String {
            if (ending.isEmpty()) return stem
            val first = ending[0]
            if (cls == 'a') {
                if (first == 'e' || first == 'é') {
                    val changed = when {
                        stem.endsWith("c") -> stem.dropLast(1) + "qu"
                        stem.endsWith("gu") -> stem.dropLast(2) + "gü"
                        stem.endsWith("g") -> stem + "u"
                        stem.endsWith("z") -> stem.dropLast(1) + "c"
                        else -> stem
                    }
                    return changed + ending
                }
                return stem + ending
            }
            if (first == 'a' || first == 'o' || first == 'á' || first == 'ó') {
                val changed = when {
                    stem.endsWith("gu") -> stem.dropLast(1)
                    stem.endsWith("qu") -> stem.dropLast(2) + "c"
                    stem.endsWith("g") -> stem.dropLast(1) + "j"
                    stem.endsWith("c") -> stem.dropLast(1) + "z"
                    else -> stem
                }
                return changed + ending
            }
            if (stem.isNotEmpty() && stem.last().isVowel() && !stem.endsWith("gu") && !stem.endsWith("qu") && first == 'i') {
                if (ending.length > 1 && (ending[1] == 'e' || ending[1] == 'ó')) return stem + "y" + ending.substring(1)
                if (stem.last() in "aeo" && (ending.length == 1 || !ending[1].isVowel())) return stem + "í" + ending.substring(1)
            }
            return stem + ending
        }

        fun forms(): List<VerbForm> {
            val out = mutableListOf<VerbForm>()
            out += VerbForm(lemma, Tense.Infinitive, null)

            val present = irregular?.pres?.let(::row) ?: run {
                val endings = when (cls) {
                    'a' -> listOf("o", "as", "a", "amos", "áis", "an")
                    'e' -> listOf("o", "es", "e", "emos", "éis", "en")
                    else -> listOf("o", "es", "e", "imos", "ís", "en")
                }
                endings.mapIndexed { index, ending ->
                    when {
                        index == 0 && zc -> stem.dropLast(1) + "zco"
                        index in STRESSED -> join(strongStem, ending)
                        else -> join(stem, ending)
                    }
                }
            }
            out.addRow(Tense.Present, present)

            val preterite: List<String?> = irregular?.pret?.let(::row)
                ?: (irregular?.strong ?: if (lemma.endsWith("ducir")) stem.dropLast(1) + "j" else null)?.let { strong ->
                    listOf(strong + "e", strong + "iste", strong + "o", strong + "imos", strong + "isteis",
                        strong + if (strong.endsWith("j")) "eron" else "ieron")
                }
                ?: if (cls == 'a') listOf("é", "aste", "ó", "amos", "asteis", "aron").map { join(stem, it) }
                else listOf(join(stem, "í"), join(stem, "iste"), join(weakStem, "ió"), join(stem, "imos"),
                    join(stem, "isteis"), join(weakStem, "ieron"))
            out.addRow(Tense.Preterite, preterite)

            val imperfect = irregular?.impf?.let(::row)
                ?: if (cls == 'a') listOf("aba", "abas", "aba", "ábamos", "abais", "aban").map { stem + it }
                else listOf("ía", "ías", "ía", "íamos", "íais", "ían").map { stem + it }
            out.addRow(Tense.Imperfect, imperfect)

            val future = irregular?.fut ?: lemma
            out.addRow(Tense.Future, listOf("é", "ás", "á", "emos", "éis", "án").map { future + it })
            out.addRow(Tense.Conditional, listOf("ía", "ías", "ía", "íamos", "íais", "ían").map { future + it })

            val subjunctive = irregular?.subj?.let(::row) ?: run {
                val first = present[0]!!
                val base = first.removeSuffix("o")
                val endings = if (cls == 'a') listOf("e", "es", "e", "emos", "éis", "en") else listOf("a", "as", "a", "amos", "áis", "an")
                val changes = irregular?.pres == null && change != null
                endings.mapIndexed { index, ending ->
                    if (index in STRESSED || !changes) {
                        if (cls == 'a') join(base, ending) else base + ending
                    } else join(if (cls == 'i') weakStem else stem, ending)
                }
            }
            out.addRow(Tense.PresentSubjunctive, subjunctive)

            preterite[5]?.let { third ->
                val base = third.removeSuffix("ron")
                out.addRow(Tense.ImperfectSubjunctive, listOf(base + "ra", base + "ras", base + "ra", accentLastVowel(base) + "ramos", base + "rais", base + "ran"))
                out.addRow(Tense.ImperfectSubjunctive, listOf(base + "se", base + "ses", base + "se", accentLastVowel(base) + "semos", base + "seis", base + "sen"))
            }

            val tu = irregular?.imp ?: present[2]
            if (tu != null && tu != "-") out += VerbForm(tu, Tense.Imperative, Person.S2)
            if (irregular?.imp != "-") out += VerbForm(lemma.dropLast(1) + "d", Tense.Imperative, Person.P2)

            val gerund = irregular?.ger ?: if (cls == 'a') stem + "ando" else join(weakStem, "iendo")
            out += VerbForm(gerund, Tense.Gerund, null)

            val part = irregular?.part ?: participle ?: if (cls == 'a') stem + "ado" else join(stem, "ido")
            out += VerbForm(part, Tense.Participle, null)
            if (part.endsWith("o")) {
                val root = part.dropLast(1)
                listOf("a", "os", "as").forEach { out += VerbForm(root + it, Tense.Participle, null) }
            }
            irregular?.extra?.let { out += it }
            return out
        }
    }

    private val STRESSED = setOf(0, 1, 2, 5)
    private val NO_ZC = setOf("hacer", "decir", "cocer", "mecer", "satisfacer", "deshacer", "rehacer")

    private fun replaceLast(stem: String, vowel: Char, with: String): String {
        val index = stem.lastIndexOf(vowel)
        return if (index < 0) stem else stem.substring(0, index) + with + stem.substring(index + 1)
    }
}

/** A few hundred common Spanish verbs. ":ie", ":ue", ":i", ":í", ":ú" mark stem changes; "p=" an irregular participle; "=" a prefixed irregular. */
private const val SPANISH_VERBS = """
ser estar ir haber tener hacer poder decir ver dar saber querer venir poner salir traer caer oír andar caber valer reír soler:ue
hablar trabajar estudiar llamar llegar llevar dejar quedar pasar tomar mirar escuchar esperar buscar necesitar usar ayudar
terminar empezar:ie comenzar:ie entrar cambiar preguntar contestar comprar pagar cocinar cenar desayunar almorzar:ue viajar
visitar caminar bailar cantar nadar tocar ganar gastar ahorrar alquilar mandar enviar:í contar:ue encontrar:ue recordar:ue
olvidar pensar:ie cerrar:ie despertar:ie sentar:ie acostar:ue levantar duchar lavar limpiar preparar arreglar invitar
celebrar organizar explicar practicar aprobar:ue probar:ue mostrar:ue costar:ue sonar:ue soñar:ue volar:ue jugar:ue acabar
intentar tratar lograr parar continuar:ú quitar echar tirar sacar colocar dibujar pintar firmar reservar cancelar confirmar
avisar amar odiar gustar encantar importar molestar interesar preocupar faltar sobrar bastar tardar durar funcionar nevar:ie
besar abrazar casar separar mudar acompañar acordar:ue apagar apuntar anotar copiar borrar grabar descansar disfrutar
aprovechar regalar prestar cuidar crear dudar votar charlar conversar avanzar alcanzar lanzar cruzar comunicar indicar
publicar aplicar dedicar equivocar desear pasear emplear mejorar empeorar aumentar notar fijar pegar cargar descargar
navegar arrancar aterrizar fumar llorar gritar saludar negar:ie regar:ie fregar:ie calentar:ie confesar:ie recomendar:ie
atravesar:ie plantar manejar aparcar presentar representar aceptar rechazar acercar alejar quejar relajar bajar montar
entregar comprobar:ue demostrar:ue renovar:ue rogar:ue colgar:ue averiguar actualizar realizar utilizar analizar situar:ú
graduar:ú actuar:ú evaluar:ú confiar:í guiar:í variar:í vaciar:í enfriar:í criar:í ensayar apoyar plantear golpear
picar facturar sudar ordenar escribir:p=escrito juntar mojar secar sumar restar contar:ue alegrar enfadar enojar cansar
aburrir estresar animar opinar participar ocupar respetar soportar aguantar matar salvar tocar terminar hallar cobrar
dejar entrenar
comer beber leer creer aprender comprender vender correr deber meter romper:p=roto responder temer coser coger escoger
recoger proteger entender:ie perder:ie volver:ue:p=vuelto devolver:ue:p=devuelto envolver:ue:p=envuelto
resolver:ue:p=resuelto mover:ue llover:ue doler:ue morder:ue torcer:ue encender:ie defender:ie atender:ie extender:ie
conocer reconocer parecer aparecer desaparecer crecer nacer ofrecer agradecer merecer obedecer pertenecer establecer
favorecer padecer convencer vencer ejercer suceder ceder proceder depender ofender sorprender emprender esconder poseer
proveer toser barrer prometer cometer someter acceder
obtener=tener mantener=tener contener=tener detener=tener sostener=tener entretener=tener retener=tener
proponer=poner suponer=poner componer=poner disponer=poner imponer=poner exponer=poner oponer=poner reponer=poner
deshacer=hacer rehacer=hacer atraer=traer distraer=traer contraer=traer prever=ver recaer=caer equivaler=valer
prevenir=venir convenir=venir intervenir=venir predecir=decir sonreír=reír
vivir describir:p=descrito inscribir:p=inscrito abrir:p=abierto cubrir:p=cubierto descubrir:p=descubierto subir recibir
decidir compartir permitir existir insistir asistir discutir sufrir cumplir dividir unir partir repartir añadir imprimir
ocurrir confundir admitir omitir resistir consistir interrumpir prohibir:í reunir:ú surgir dirigir exigir fingir
corregir:i elegir:i seguir:i conseguir:i perseguir:i pedir:i repetir:i servir:i vestir:i medir:i despedir:i impedir:i
competir:i sentir:ie mentir:ie preferir:ie divertir:ie convertir:ie advertir:ie consentir:ie hervir:ie herir:ie
sugerir:ie referir:ie requerir:ie dormir:ue morir:ue:p=muerto construir destruir incluir concluir excluir contribuir
distribuir huir sustituir influir disminuir atribuir instruir conducir traducir producir reducir introducir deducir lucir
sobrevivir convivir transmitir emitir acudir aplaudir hundir batir debatir combatir
"""
