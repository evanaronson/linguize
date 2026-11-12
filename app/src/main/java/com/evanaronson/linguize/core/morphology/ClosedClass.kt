package com.evanaronson.linguize.core.morphology

import com.evanaronson.linguize.core.corpus.Lexeme
import com.evanaronson.linguize.core.corpus.Pos

/**
 * Lab: function words (articles, pronouns, determiners, prepositions, conjunctions, common
 * adverbs and numbers), hand-written. Lemmas follow the Spanish frequency deck: articles under
 * "el" and "un", object pronouns under their subject form ("la" is "ella", "lo" and "le" are
 * "él", "me" is "yo"), possessives under the long form ("mi" is "mío"). Catalan follows the same
 * conventions ("els" is the article "el" and the pronoun "ell"). Homographs keep every lexeme:
 * Spanish "la" is {el/art, ella/pron}. Contractions are both parts: "del" is {de/prep, el/art}.
 */
internal object ClosedClass {
    fun lexemes(word: String, language: String): Set<Lexeme>? = TABLES[language]?.value?.get(word)

    private val TABLES = mapOf("es" to lazy { parse(SPANISH) }, "ca" to lazy { parse(CATALAN) })

    /** Lines of "lemma/pos: form form…"; the lemma is itself a form. */
    private fun parse(source: String): Map<String, Set<Lexeme>> {
        val table = HashMap<String, MutableSet<Lexeme>>()
        source.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val (head, forms) = line.split(':', limit = 2).let { it[0].trim() to it.getOrElse(1) { "" } }
            val (lemma, pos) = head.split('/').let { it[0] to Pos.of(it[1]) }
            (listOf(lemma) + forms.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }).forEach { form ->
                table.getOrPut(form) { linkedSetOf() } += Lexeme(lemma, pos)
            }
        }
        return table
    }

    private val SPANISH = """
        el/art: la los las lo
        un/art: una unos unas
        de/prep: del
        a/prep: al
        el/art: del al
        yo/pron: me
        tú/pron: te ti contigo
        él/pron: lo le los les ellos
        ella/pron: la las ellas
        nosotros/pron: nos nosotras
        vosotros/pron: os vosotras
        usted/pron: ustedes
        se/pron:
        sí/pron:
        mí/pron: conmigo
        ello/pron:
        que/pron:
        qué/pron:
        quien/pron: quienes
        quién/pron: quiénes
        cual/pron: cuales
        cuál/pron: cuáles
        algo/pron:
        nada/pron:
        alguien/pron:
        nadie/pron:
        mío/det: mi mis mía míos mías
        tuyo/det: tu tus tuya tuyos tuyas
        suyo/det: su sus suya suyos suyas
        nuestro/det: nuestra nuestros nuestras
        vuestro/det: vuestra vuestros vuestras
        este/det: esta estos estas
        ese/det: esa esos esas
        aquel/det: aquella aquellos aquellas
        éste/pron: esto ésta éstos éstas
        ése/pron: eso ésa ésos ésas
        aquello/pron:
        todo/det: toda todos todas
        otro/det: otra otros otras
        cada/det:
        alguno/det: alguna algún algunos algunas
        ninguno/det: ninguna ningún
        cualquiera/det: cualquier
        varios/det: varias
        mucho/det: mucha muchos muchas
        poco/det: poca pocos pocas
        cuánto/det: cuánta cuántos cuántas
        de/prep:
        a/prep:
        en/prep:
        por/prep:
        para/prep:
        con/prep:
        sin/prep:
        sobre/prep:
        entre/prep:
        hasta/prep:
        desde/prep:
        hacia/prep:
        contra/prep:
        durante/prep:
        según/prep:
        ante/prep:
        bajo/prep:
        tras/prep:
        y/conj: e
        o/conj: u
        pero/conj:
        si/conj:
        como/conj:
        cuando/conj:
        porque/conj:
        aunque/conj:
        ni/conj:
        sino/conj:
        mientras/conj:
        pues/conj:
        no/adv:
        sí/adv:
        mucho/adv: muy
        bien/adv:
        mal/adv:
        más/adv:
        menos/adv:
        ya/adv:
        aquí/adv:
        ahí/adv:
        allí/adv:
        ahora/adv:
        hoy/adv:
        ayer/adv:
        mañana/adv:
        siempre/adv:
        nunca/adv:
        también/adv:
        tampoco/adv:
        todavía/adv:
        aún/adv:
        así/adv:
        entonces/adv:
        luego/adv:
        después/adv:
        antes/adv:
        solo/adv: sólo
        tan/adv:
        tanto/adv:
        casi/adv:
        cómo/adv:
        dónde/adv:
        cuándo/adv:
        donde/pron:
        uno/num: un
        dos/num:
        tres/num:
        cuatro/num:
        cinco/num:
        hola/interj:
        adiós/interj:
        gracias/interj:
        vale/interj:
        oye/interj:
        bueno/interj:
        ojalá/interj:
        muy/adv:
        fuera/adv:
        dentro/adv:
        cerca/adv:
        lejos/adv:
        arriba/adv:
        abajo/adv:
        atrás/adv:
        delante/adv:
        detrás/adv:
        encima/adv:
        debajo/adv:
        pronto/adv:
        tarde/adv:
        quizá/adv: quizás
        demasiado/adv:
        bastante/adv:
        apenas/adv:
        jamás/adv:
        incluso/adv:
        además/adv:
        él/pron:
        ella/pron:
        yo/pron:
        tú/pron:
        usted/pron:
        tal/pron:
        seis/num:
        siete/num:
        ocho/num:
        nueve/num:
        diez/num:
        cien/num: ciento
        mil/num:
    """

    private val CATALAN = """
        el/art: la l els les lo
        un/art: una uns unes
        de/prep: d del dels
        a/prep: al als
        per/prep: pel pels
        el/art: del dels al als pel pels
        jo/pron: em m me mi
        tu/pron: et t te
        ell/pron: el l lo li els ells
        ella/pron: la les elles
        nosaltres/pron: ens ns nos
        vosaltres/pron: us vos
        vostè/pron: vostès
        es/pron: s se
        ho/pron:
        hi/pron:
        en/pron: n ne
        que/pron:
        què/pron:
        qui/pron:
        quin/det: quina quins quines
        res/pron:
        algú/pron:
        ningú/pron:
        això/pron:
        allò/pron:
        meu/det: meva meus meves mon ma
        teu/det: teva teus teves
        seu/det: seva seus seves
        nostre/det: nostra nostres
        vostre/det: vostra vostres
        aquest/det: aquesta aquests aquestes
        aquell/det: aquella aquells aquelles
        tot/det: tota tots totes
        altre/det: altra altres
        cada/det:
        algun/det: alguna alguns algunes
        cap/det:
        molt/det: molta molts moltes
        poc/det: poca pocs poques
        quant/det: quanta quants quantes
        en/prep:
        amb/prep:
        sense/prep:
        sobre/prep:
        entre/prep:
        fins/prep:
        des/prep:
        cap/prep:
        contra/prep:
        durant/prep:
        segons/prep:
        i/conj:
        o/conj:
        però/conj:
        si/conj:
        com/conj:
        quan/conj:
        perquè/conj:
        encara/adv:
        ni/conj:
        sinó/conj:
        doncs/conj:
        mentre/conj:
        no/adv:
        sí/adv:
        molt/adv:
        bé/adv:
        malament/adv:
        més/adv:
        menys/adv:
        ja/adv:
        aquí/adv:
        allà/adv:
        ara/adv:
        avui/adv:
        ahir/adv:
        demà/adv:
        sempre/adv:
        mai/adv:
        també/adv:
        tampoc/adv:
        així/adv:
        llavors/adv:
        després/adv:
        abans/adv:
        només/adv:
        potser/adv:
        aviat/adv:
        tan/adv:
        tant/adv:
        gairebé/adv:
        on/adv:
        un/num:
        dos/num: dues
        tres/num:
        quatre/num:
        cinc/num:
        hola/interj:
        adéu/interj:
        gràcies/interj:
        sisplau/interj:
        esclar/interj:
        vinga/interj:
        vaja/interj:
        oi/interj:
        oh/interj:
        ei/interj:
        eh/interj:
        ah/interj:
        ai/interj:
        bo/adj: bon
        bé/adv: ben
        mal/adv:
        fora/adv:
        prou/adv:
        massa/adv:
        gaire/adv:
        prop/adv:
        lluny/adv:
        dalt/adv:
        baix/adv:
        tard/adv:
        aviat/adv:
        ací/adv:
        allí/adv:
        endavant/adv:
        enrere/adv:
        amunt/adv:
        avall/adv:
        sota/prep:
        davant/prep:
        darrere/prep:
        damunt/prep:
        malgrat/prep:
        excepte/prep:
        tothom/pron:
        quelcom/pron:
        vós/pron:
        açò/pron:
        qual/pron: quals
        tal/det: tals
        sis/num:
        set/num:
        vuit/num:
        nou/num:
        deu/num:
        cent/num:
        mil/num:
    """
}
