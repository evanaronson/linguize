package com.evanaronson.linguize.core.morphology

/**
 * Lab: nouns and adjectives. Common adjectives are listed in the masculine singular and their
 * feminine and plural forms are generated, so "buenas" and "contentes" fold to "bueno" and
 * "content". Other words ending in a plural are folded to a singular by rule ([singular]);
 * gender is folded only for listed adjectives, since "casa" is not a form of "caso".
 */
internal object Nominal {
    fun adjectiveForms(language: String): List<Pair<String, String>> = when (language) {
        "es" -> SPANISH_ADJECTIVES.words().flatMap { lemma -> spanishAdjective(lemma).map { it to lemma } }
        "ca" -> CATALAN_ADJECTIVES.words().flatMap { entry ->
            val (lemma, feminine) = entry.split('/').let { it[0] to it.getOrNull(1) }
            catalanAdjective(lemma, feminine).map { it to lemma }
        }
        else -> emptyList()
    }

    private fun String.words() = trim().split(Regex("\\s+"))

    fun spanishAdjective(lemma: String): List<String> {
        val feminine = when {
            lemma.endsWith("o") -> lemma.dropLast(1) + "a"
            lemma.endsWith("ón") || lemma.endsWith("án") || lemma.endsWith("és") || lemma.endsWith("ín") -> stripAccents(lemma) + "a"
            lemma.endsWith("or") && lemma !in COMPARATIVES -> lemma + "a"
            lemma.endsWith("ol") -> lemma + "a"
            else -> null
        }
        return listOfNotNull(lemma, feminine, spanishPlural(lemma), feminine?.let { it + "s" }).distinct()
    }

    fun spanishPlural(word: String): String = when {
        word.last().isVowel() && word.last() !in "íú" -> word + "s"
        word.endsWith("z") -> word.dropLast(1) + "ces"
        word.endsWith("ón") || word.endsWith("án") || word.endsWith("és") || word.endsWith("ín") -> stripAccents(word) + "es"
        word == "joven" -> "jóvenes"
        else -> word + "es"
    }

    fun catalanAdjective(lemma: String, given: String?): List<String> {
        val feminine = given ?: when {
            lemma.endsWith("e") || lemma.endsWith("a") || lemma.endsWith("ble") -> null
            lemma.endsWith("ós") || lemma.endsWith("ès") -> stripAccents(lemma) + "a"
            lemma.endsWith("iu") -> lemma.dropLast(1) + "va"
            lemma.endsWith("at") -> lemma.dropLast(1) + "da"
            else -> lemma + "a"
        }
        val masculinePlural = when {
            lemma == "bo" -> "bons"
            lemma.last() in "àéèíóòú" -> stripAccents(lemma) + "ns"
            lemma.endsWith("ós") || lemma.endsWith("ès") -> stripAccents(lemma) + "os"
            lemma.endsWith("s") || lemma.endsWith("x") || lemma.endsWith("ç") -> lemma + "os"
            else -> lemma + "s"
        }
        val femininePlural = (feminine ?: lemma).let(::catalanFemininePlural)
        return listOfNotNull(lemma, feminine, masculinePlural, femininePlural).distinct()
    }

    /** groga becomes grogues, plaça places, pluja pluges, casa cases. */
    fun catalanFemininePlural(word: String): String = when {
        word.endsWith("ca") -> word.dropLast(2) + "ques"
        word.endsWith("ga") -> word.dropLast(2) + "gues"
        word.endsWith("ça") -> word.dropLast(2) + "ces"
        word.endsWith("ja") -> word.dropLast(2) + "ges"
        word.endsWith("gua") -> word.dropLast(3) + "gües"
        word.endsWith("qua") -> word.dropLast(3) + "qües"
        word.endsWith("a") -> word.dropLast(1) + "es"
        word.endsWith("e") -> word + "s"
        else -> word + "s"
    }

    /**
     * A singular for a word that looks plural, or null. A rule, not a dictionary: "ciudades"
     * gives "ciudad", "canciones" "canción", "cases" "casa", "cançons" "cançó".
     */
    fun singular(word: String, language: String): String? {
        if (word.length < 4 || !word.endsWith("s")) return null
        return when (language) {
            "es" -> spanishSingular(word)
            "ca" -> catalanSingular(word)
            else -> null
        }
    }

    private fun spanishSingular(word: String): String? {
        if (word in SPANISH_INVARIABLE) return null
        val beforeS = word[word.length - 2]
        // país, inglés, autobús: a stressed last syllable is singular.
        if (beforeS in "áéíóú") return null
        return when {
            word.endsWith("iones") -> word.dropLast(5) + "ión"
            word.endsWith("ces") && word.length > 4 -> word.dropLast(3) + "z"
            word.endsWith("enes") && word.any { it in "áéíóú" } -> stripAccents(word.dropLast(2))
            word.endsWith("es") && word[word.length - 3] in "lnrdjy" -> word.dropLast(2)
            beforeS.isVowel() -> word.dropLast(1)
            else -> null
        }
    }

    private fun catalanSingular(word: String): String? {
        if (word in CATALAN_INVARIABLE) return null
        if (word[word.length - 2] in "àéèíóòú") return null
        return when {
            word.endsWith("ons") -> word.dropLast(3) + "ó"
            word.endsWith("ins") -> word.dropLast(3) + "í"
            word.endsWith("ques") -> word.dropLast(4) + "ca"
            word.endsWith("gues") -> word.dropLast(4) + "ga"
            word.endsWith("gües") -> word.dropLast(4) + "gua"
            word.endsWith("ces") -> word.dropLast(3) + "ça"
            word.endsWith("ges") -> word.dropLast(3) + "ja"
            word.endsWith("es") -> {
                val stem = word.dropLast(2)
                if (stem + "e" in CATALAN_E_NOUNS) stem + "e" else stem + "a"
            }
            word.endsWith("sos") || word.endsWith("ços") || word.endsWith("xos") -> word.dropLast(2)
            else -> word.dropLast(1)
        }
    }

    private val COMPARATIVES = setOf("mejor", "peor", "mayor", "menor", "superior", "inferior", "anterior", "posterior", "exterior", "interior")
    private val SPANISH_INVARIABLE = setOf(
        "lunes", "martes", "miércoles", "jueves", "viernes", "crisis", "análisis", "tesis", "virus", "paraguas",
        "cumpleaños", "menos", "antes", "después", "pues", "entonces", "además", "quizás", "nosotros", "vosotros",
        "ellos", "ellas", "ustedes", "todos", "todas", "unos", "unas", "estos", "estas", "esos", "esas", "aquellos",
        "aquellas", "nuestros", "nuestras", "vuestros", "vuestras", "tres", "seis", "dos", "mis", "tus", "sus", "les",
        "los", "las", "nos", "jamás", "atrás", "detrás", "lejos", "gracias", "tos", "mes", "gas", "bus", "dios",
    )
    private val CATALAN_INVARIABLE = setOf(
        "dilluns", "dimarts", "dimecres", "dijous", "divendres", "dissabte", "diumenge", "després", "abans", "menys",
        "més", "potser", "doncs", "nosaltres", "vosaltres", "ells", "elles", "vostès", "tots", "totes", "uns", "unes",
        "aquests", "aquestes", "aquells", "aquelles", "tres", "dos", "dues", "sis", "els", "les", "mes", "pas", "gas",
        "llapis", "temps", "cos", "tos", "països", "fins", "mentres", "gràcies", "pis", "nas", "rus", "anglès",
    )

    /** Catalan nouns in -e, so "pares" folds to "pare", not "para". */
    private val CATALAN_E_NOUNS = setOf(
        "pare", "mare", "home", "llibre", "cotxe", "classe", "frase", "arbre", "nombre", "noble", "pobre", "jove",
        "alegre", "base", "metre", "sobre", "febre", "fase", "torre", "sastre", "monstre", "teatre", "centre", "cine",
        "vespre", "aire", "deure", "oncle", "sabre", "pebre", "sucre", "ordre", "tigre", "nòmade",
    )
}

private const val SPANISH_ADJECTIVES = """
bueno malo nuevo viejo pequeño grande alto bajo largo corto gordo delgado guapo feo bonito lindo rico pobre caro barato
fácil difícil rápido lento fuerte débil joven mayor feliz triste contento cansado enfermo listo tonto simpático
antipático amable tranquilo nervioso ocupado libre lleno vacío limpio sucio caliente frío fresco seco mojado oscuro claro
blanco negro rojo azul verde amarillo gris rosa naranja cierto seguro importante necesario posible imposible
próximo último primero segundo tercero propio mismo otro poco mucho todo mejor peor solo único especial
normal raro extraño perfecto horrible terrible increíble maravilloso precioso divertido aburrido interesante gracioso
serio sincero honesto educado tímido valiente generoso trabajador hablador español inglés francés alemán catalán
italiano portugués americano europeo cercano lejano abierto cerrado roto preocupado enfadado enamorado casado soltero
dulce salado amargo picante sano fino grueso ancho estrecho profundo suave duro blando ligero pesado moderno antiguo
actual público privado social personal local nacional internacional general principal real verdadero
falso exacto correcto incorrecto equivocado sencillo complicado completo entero medio doble
"""

/** "masculine" or "masculine/feminine" when the feminine is not regular. */
private const val CATALAN_ADJECTIVES = """
bo/bona dolent nou/nova vell/vella petit gran alt baix llarg curt gras/grassa prim guapo/guapa lleig/lletja bonic ric/rica
pobre car barat fàcil difícil ràpid lent fort feble jove content/contenta trist cansat malalt llest ximple simpàtic
amable tranquil nerviós ocupat lliure ple/plena buit/buida net brut calent fred fresc sec/seca moll fosc clar blanc negre/negra
vermell blau/blava verd groc/groga gris marró rosa taronja cert segur important necessari possible impossible proper
últim primer segon tercer propi mateix altre/altra poc molt tot/tota qualsevol millor pitjor sol únic especial normal
rar estrany perfecte/perfecta horrible terrible increïble meravellós preciós divertit/divertida avorrit/avorrida
interessant seriós sincer honest educat tímid valent generós treballador xerraire català/catalana espanyol anglès
francès alemany italià/italiana portuguès americà/americana europeu/europea proper llunyà/llunyana obert tancat trencat
preocupat enfadat enamorat casat solter/soltera dolç/dolça salat amarg picant gruixut ample estret
profund suau dur tou/tova lleuger/lleugera pesat modern antic/antiga actual públic privat social personal local nacional
internacional general principal real veritable fals/falsa exacte/exacta correcte/correcta incorrecte/incorrecta
equivocat senzill/senzilla complicat complet/completa sencer/sencera mig/mitja doble
"""
