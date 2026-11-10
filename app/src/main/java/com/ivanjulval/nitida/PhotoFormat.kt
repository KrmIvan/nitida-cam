package com.ivanjulval.nitida

/**
 * Formatos de foto con su proporción, el tamaño recomendado y dónde se usan.
 * Medidas recomendadas por las redes en 2026.
 * ratioW:ratioH es la forma de la foto final (ancho:alto). 0:0 = sin recortar.
 */
enum class PhotoFormat(
    val label: String,
    val ratioW: Int,
    val ratioH: Int,
    val targetW: Int,
    val targetH: Int,
    val uses: String,
) {
    FULL(
        "Completo", 0, 0, 1080, 1440,
        "Todo el sensor, máxima resolución. En vertical es 3:4, que es justo lo que muestra la " +
            "cuadrícula del perfil de Instagram sin recortar (1080 × 1440)."
    ),
    STORY(
        "Historia", 9, 16, 1080, 1920,
        "Historias y Reels de Instagram y Facebook, TikTok, YouTube Shorts y estados de WhatsApp. " +
            "Deja libre la parte de arriba y de abajo, que la tapan los botones."
    ),
    POST(
        "Publicación", 4, 5, 1080, 1350,
        "Publicación vertical del feed de Instagram, Facebook y LinkedIn. Es la que más pantalla ocupa al hacer scroll."
    ),
    SQUARE(
        "Cuadrado", 1, 1, 1080, 1080,
        "Fotos de perfil, carruseles y publicaciones cuadradas. También va bien para fotos de productos."
    ),
    PIN(
        "Pinterest", 2, 3, 1000, 1500,
        "Pines de Pinterest y pósters o carteles verticales."
    ),
    WIDE(
        "Horizontal", 16, 9, 1920, 1080,
        "Miniaturas de YouTube, publicaciones de X/Twitter, fondos de pantalla del PC y presentaciones. " +
            "Gira el móvil para aprovechar toda la foto."
    );

    val ratioText: String get() = if (ratioW == 0) "3:4" else "$ratioW:$ratioH"
    val sizeText: String get() = "$targetW × $targetH px"
}
