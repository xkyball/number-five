package com.no5.helm.ui

import com.no5.helm.chart.ChartPresentation
import com.no5.helm.chart.ChartStyle

/** Palette swatches copied from the packaged presentation.xml, not rendered chart imagery.
 * Keep the source hash alongside each style; source-contract tests detect palette drift.
 */
internal data class ChartStylePreviewPalette(
    val styleId: String, val sourceAsset: String, val sourceSha256: String,
    val deep: Int, val middle: Int, val shallow: Int, val veryShallow: Int,
    val land: Int, val coast: Int, val contour: Int, val ink: Int,
    val treatment: Int, val coastWidth: Float,
)
internal object ChartStylePreviewPalettes {
    val all: Map<String, ChartStylePreviewPalette> = mapOf(
        "default" to ChartStylePreviewPalette("default", "presentation/default-default/presentation.xml", "2d82f58b558410c4cc2a722f034b56e6fe50ce52ab98a178602aa6436537787a",
            0xFFD4EAEE.toInt(), 0xFFBAD5E1.toInt(), 0xFF98C5F2.toInt(), 0xFF73B6EF.toInt(), 0xFFC9B97A.toInt(), 0xFF525A5C.toInt(), 0xFF7D898C.toInt(), 0xFF070707.toInt(), 0, 1.0f),
        "glacier" to ChartStylePreviewPalette("glacier", "presentation/glacier-default/presentation.xml", "9e642afd47e8341dbcb0ee7dc5fb7b904b549c1e0b8537da9b940e55147da4d8",
            0xFFF2F8FA.toInt(), 0xFFD5EAF2.toInt(), 0xFFACD2E8.toInt(), 0xFF75AFD3.toInt(), 0xFFE1E3DB.toInt(), 0xFF465359.toInt(), 0xFF6C8898.toInt(), 0xFF070707.toInt(), 0, 1.0f),
        "signal" to ChartStylePreviewPalette("signal", "presentation/signal-default/presentation.xml", "4cef8462ca472e943fd49ca98e5cba1ec46fc99edfb5f35bd2b9ba544edf099a",
            0xFFFBFDFF.toInt(), 0xFFD9EDF8.toInt(), 0xFFAFD3E9.toInt(), 0xFF7EAFD1.toInt(), 0xFFD9DEE2.toInt(), 0xFF25323B.toInt(), 0xFF6B8496.toInt(), 0xFF101820.toInt(), 0, 3.0f),
        "paper" to ChartStylePreviewPalette("paper", "presentation/paper-default/presentation.xml", "a464d98bdfa1126bca3036e092ad5f0298d2d7afcc676bb77112e961991b8b51",
            0xFFEAF6FF.toInt(), 0xFFC7E4F6.toInt(), 0xFF99C8E5.toInt(), 0xFF65A1CA.toInt(), 0xFFF2F3F1.toInt(), 0xFF25323B.toInt(), 0xFF65869D.toInt(), 0xFF101820.toInt(), 0, 3.0f),
        "contour" to ChartStylePreviewPalette("contour", "presentation/contour-default/presentation.xml", "030037f929d46616458f4c76c1b167b11283634c744f34c40452ece2f4fc1ef1",
            0xFFF7FDFF.toInt(), 0xFFC5EAF2.toInt(), 0xFF8BD4E2.toInt(), 0xFF58B4CB.toInt(), 0xFFD3DFDA.toInt(), 0xFF184553.toInt(), 0xFF5094A8.toInt(), 0xFF101820.toInt(), 0, 3.0f),
        "lagoon" to ChartStylePreviewPalette("lagoon", "presentation/lagoon-default/presentation.xml", "4a8a87bf0de3ab7da5919db029ad976e602e613bd833e35594164b60e5049664",
            0xFF69C4DA.toInt(), 0xFF92DDE5.toInt(), 0xFFB9EEE9.toInt(), 0xFFE0F7EE.toInt(), 0xFFCBC7B7.toInt(), 0xFF173F4D.toInt(), 0xFF457E8B.toInt(), 0xFF101820.toInt(), 0, 3.0f),
        "cream" to ChartStylePreviewPalette("cream", "presentation/cream-default/presentation.xml", "3ef88d6ef6edec3d1698f28635a20c25f7d6ad9cbad6f7a620071ab42dd0bbdd",
            0xFF8ABFC7.toInt(), 0xFFA4D3D7.toInt(), 0xFFC1E4E3.toInt(), 0xFFE0F1E9.toInt(), 0xFFF1E7CD.toInt(), 0xFF647E7D.toInt(), 0xFF537F88.toInt(), 0xFF233B43.toInt(), 0, 3.0f),
        "sculpt-flat" to ChartStylePreviewPalette("sculpt-flat", "presentation/sculpt-flat-default/presentation.xml", "a8b3f46ecbaf1c30cb3c24d59901b47d85a933e5615c67df35153c83d368737d",
            0xFF116282.toInt(), 0xFF159DAF.toInt(), 0xFF66D7D8.toInt(), 0xFFC5F4E9.toInt(), 0xFFC8C7B2.toInt(), 0xFF203B40.toInt(), 0xFF416E7D.toInt(), 0xFFE1F2F2.toInt(), 0, 3.0f),
        "sculpt" to ChartStylePreviewPalette("sculpt", "presentation/sculpt-default/presentation.xml", "c10fb5a6864c730826cbbb062740accbbd301f7ce16621f8d8367c1da5817051",
            0xFF116282.toInt(), 0xFF159DAF.toInt(), 0xFF66D7D8.toInt(), 0xFFC5F4E9.toInt(), 0xFFC8C7B2.toInt(), 0xFF203B40.toInt(), 0xFF416E7D.toInt(), 0xFFE1F2F2.toInt(), 1, 3.0f),
        "lagoon-sculpt" to ChartStylePreviewPalette("lagoon-sculpt", "presentation/lagoon-sculpt-r04/presentation.xml", "60e3ab74d4bc6e3ed2633cf9d8c9a8f1c310d8a7a15154c778194cb8563f7b2d",
            0xFF116282.toInt(), 0xFF159DAF.toInt(), 0xFF66D7D8.toInt(), 0xFFC5F4E9.toInt(), 0xFFC8C7B2.toInt(), 0xFF203B40.toInt(), 0xFF416E7D.toInt(), 0xFFE1F2F2.toInt(), 0, 3.0f),
        "prism-flat" to ChartStylePreviewPalette("prism-flat", "presentation/prism-flat-default/presentation.xml", "64d832ee1200e737884088b15c3fc7372b118bb80b5e102aab15cebc5a4ced75",
            0xFF074369.toInt(), 0xFF08748B.toInt(), 0xFF21B8C7.toInt(), 0xFF9FF6E7.toInt(), 0xFF252E36.toInt(), 0xFF86DED5.toInt(), 0xFF63B7C7.toInt(), 0xFFD9F4F1.toInt(), 0, 3.0f),
        "prism" to ChartStylePreviewPalette("prism", "presentation/prism-default/presentation.xml", "52dce25d6d0215b61bab5ca7e2ce87c944619f7bc50b1a0e58b37e457b07680e",
            0xFF074369.toInt(), 0xFF08748B.toInt(), 0xFF21B8C7.toInt(), 0xFF9FF6E7.toInt(), 0xFF252E36.toInt(), 0xFF86DED5.toInt(), 0xFF63B7C7.toInt(), 0xFFD9F4F1.toInt(), 2, 3.0f),
        "redwatch" to ChartStylePreviewPalette("redwatch", "presentation/redwatch-default/presentation.xml", "91a74b424408a100d70beade3867b8cb771ba83cb6519783cfdeac33521039eb",
            0xFF070102.toInt(), 0xFF160305.toInt(), 0xFF2E070B.toInt(), 0xFF4E0C12.toInt(), 0xFF180506.toInt(), 0xFF8B3C39.toInt(), 0xFF59242A.toInt(), 0xFFB46058.toInt(), 0, 3.0f),
        "redline" to ChartStylePreviewPalette("redline", "presentation/redline-default/presentation.xml", "40b6665df9ce4ebf2d0dd04dac20abeb8c5dc6a50f9642671673b20137ef1540",
            0xFF100204.toInt(), 0xFF24060A.toInt(), 0xFF3E0A10.toInt(), 0xFF65121B.toInt(), 0xFF090203.toInt(), 0xFFA74942.toInt(), 0xFF79333A.toInt(), 0xFFD07968.toInt(), 0, 3.0f),
        "redshore" to ChartStylePreviewPalette("redshore", "presentation/redshore-default/presentation.xml", "f261885962d194e5ceae721dc394ec3702c7bcf021165b47a1fc63f6ea7db520",
            0xFF050102.toInt(), 0xFF100307.toInt(), 0xFF1D0710.toInt(), 0xFF300B16.toInt(), 0xFF682F31.toInt(), 0xFFEFAF99.toInt(), 0xFF632934.toInt(), 0xFFC97669.toInt(), 0, 3.0f),
        "redwater" to ChartStylePreviewPalette("redwater", "presentation/redwater-default/presentation.xml", "6e2f9c34bdf56581eab5b69278f062916f8e1ff9811b584a61813681edf288dd",
            0xFF240306.toInt(), 0xFF39050A.toInt(), 0xFF530910.toInt(), 0xFF711119.toInt(), 0xFF030102.toInt(), 0xFFA76254.toInt(), 0xFF71332E.toInt(), 0xFFC17768.toInt(), 0, 3.0f),
        "redwater-focus" to ChartStylePreviewPalette("redwater-focus", "presentation/redwater-focus-default/presentation.xml", "cab2e9d7d53bf8b54dc82f57d87a48aeff799b61c76dd652009863f8bc0dc481",
            0xFF220407.toInt(), 0xFF32060B.toInt(), 0xFF4B0A11.toInt(), 0xFF68111B.toInt(), 0xFF020102.toInt(), 0xFF86473D.toInt(), 0xFF71332E.toInt(), 0xFFBC7666.toInt(), 0, 3.0f),
        "redwater-gray" to ChartStylePreviewPalette("redwater-gray", "presentation/redwater-gray-default/presentation.xml", "58f6051c1a959aa3a3c9ab1223ad41f75f3644332a17f7eae0de1a5deee2bf04",
            0xFF050505.toInt(), 0xFF0B0B0B.toInt(), 0xFF151515.toInt(), 0xFF1F1F1F.toInt(), 0xFF010101.toInt(), 0xFF464646.toInt(), 0xFF282828.toInt(), 0xFF545454.toInt(), 0, 3.0f),
        "redwater-focus-gray" to ChartStylePreviewPalette("redwater-focus-gray", "presentation/redwater-focus-gray-default/presentation.xml", "d667466959c508c86dbc54fab44db561cad5c39cafaa0397feac919f350468da",
            0xFF050505.toInt(), 0xFF0A0A0A.toInt(), 0xFF121212.toInt(), 0xFF1D1D1D.toInt(), 0xFF000000.toInt(), 0xFF343434.toInt(), 0xFF282828.toInt(), 0xFF535353.toInt(), 0, 3.0f),
    )
    fun require(styleId: String): ChartStylePreviewPalette = checkNotNull(all[styleId]) {
        "No preview palette for chart style: $styleId"
    }
}

/** Preserve compatible choices; selecting S01 restores its required original R04 symbols. */
internal fun ChartPresentation.withStyle(style: ChartStyle): ChartPresentation = copy(
    styleId = style.id,
    symbols = symbols.takeIf { it in style.symbolSets } ?: style.symbolSets.first(),
    landRelief = landRelief && style.allowsLandRelief,
)
