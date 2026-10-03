package io.github.stardomains3.oxproxion

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpContinuationTest {

    @Test fun nothingAddedLeavesTheReplyAlone() {
        assertEquals("She smiles.", RpContinuation.join("She smiles.", ""))
    }

    @Test fun anEmptyReplyIsJustTheAddition() {
        assertEquals("Hello", RpContinuation.join("", "Hello"))
    }

    @Test fun aFinishedSentenceStartsANewParagraph() {
        assertEquals("She smiles.\n\nThen she turns.", RpContinuation.join("She smiles.", "Then she turns."))
    }

    @Test fun aClosedActionStartsANewParagraphBeforeDialogue() {
        assertEquals("*She smiles.*\n\n\"Come in.\"", RpContinuation.join("*She smiles.*", "\"Come in.\""))
        assertEquals("\"Come in.\"\n\nShe waits.", RpContinuation.join("\"Come in.\"", "She waits."))
    }

    @Test fun unfinishedTextGetsASpaceBeforeTheNextWord() {
        assertEquals("She waits and", RpContinuation.join("She waits", "and"))
    }

    @Test fun whitespaceTheModelSentIsKept() {
        assertEquals("She smiles.\n\nThen she turns.", RpContinuation.join("She smiles.", "\n\nThen she turns."))
        assertEquals("She smiles. Then", RpContinuation.join("She smiles. ", "Then"))
    }

    @Test fun punctuationHugsTheWordBeforeIt() {
        assertEquals("She waits, and", RpContinuation.join("She waits", ", and"))
        assertEquals("Really?!", RpContinuation.join("Really", "?!"))
    }

    @Test fun anOpenDashOrBracketTakesTheNextWordDirectly() {
        assertEquals("Wait—what", RpContinuation.join("Wait—", "what"))
        assertEquals("(quietly)", RpContinuation.join("(", "quietly)"))
    }

    @Test fun aFinishedJapaneseSentenceStartsANewParagraph() {
        assertEquals("彼女は微笑む。\n\nそして振り向く。", RpContinuation.join("彼女は微笑む。", "そして振り向く。"))
        assertEquals("「来て。」\n\n彼は待つ。", RpContinuation.join("「来て。」", "彼は待つ。"))
    }

    @Test fun unfinishedJapaneseAndKoreanDoNotGainASpace() {
        assertEquals("彼女は待つそして", RpContinuation.join("彼女は待つ", "そして"))
        assertEquals("안녕잘 가", RpContinuation.join("안녕", "잘 가"))
    }

    @Test fun japanesePunctuationHugsTheWordBeforeIt() {
        assertEquals("彼女は待つ。", RpContinuation.join("彼女は待つ", "。"))
    }

    @Test fun continueKeepsThePictureAlreadyOnTheReply() {
        val piece = FlexibleMessage(role = "assistant", content = JsonPrimitive("She turns."))
        val kept = RpContinuation.keepPicture("content://scene/1", piece)
        assertEquals("content://scene/1", kept.imageUri)
        assertEquals("She turns.", (kept.content as JsonPrimitive).content)
        val fresh = piece.copy(imageUri = "content://scene/2")
        assertEquals("content://scene/2", RpContinuation.keepPicture("content://scene/1", fresh).imageUri)
        assertEquals(null, RpContinuation.keepPicture("data:image/jpeg;base64,qq", piece).imageUri)
    }

    @Test fun continueKeepsTheJpegStoredOnTheReply() {
        val jpeg = "data:image/jpeg;base64,qq"
        val prior = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "She holds it up.")
            })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", jpeg) })
            })
        }
        val piece = FlexibleMessage(role = "assistant", content = JsonPrimitive("She turns."))
        val kept = RpContinuation.keepPicture("content://scene/1", piece, prior)
        assertEquals("content://scene/1", kept.imageUri)
        assertEquals("She turns.", MessageContent.text(kept.content))
        assertEquals(jpeg, MessageContent.imageUrl(kept.content))
        val wire = kept.toApiMessage()
        assertFalse(MessageContent.hasImage(wire.content))
        assertFalse(wire.content.toString().contains("qq"))
    }

    @Test fun swipeKeepsThePictureStoredOnTheReply() {
        val jpeg = "data:image/jpeg;base64,qq"
        val prior = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "She holds it up.")
            })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", jpeg) })
            })
        }
        val message = FlexibleMessage(
            role = "assistant",
            content = prior,
            imageUri = "content://scene/1",
            reasoning = "hmm",
        )
        val next = RpContinuation.withWords(message, "She turns.")
        assertEquals("She turns.", MessageContent.text(next.content))
        assertEquals(jpeg, MessageContent.imageUrl(next.content))
        assertEquals("content://scene/1", next.imageUri)
        assertEquals(null, next.reasoning)
        assertEquals(null, next.thinking)
        val plain = RpContinuation.withWords(
            FlexibleMessage(role = "assistant", content = JsonPrimitive("Hello")),
            "Hello again",
        )
        assertEquals("Hello again", MessageContent.text(plain.content))
        assertEquals(null, MessageContent.imageUrl(plain.content))
    }

    @Test fun eachSwipeVersionKeepsItsOwnPicture() {
        val jpeg = "data:image/jpeg;base64,qq"
        val prior = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "She holds it up.")
            })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", jpeg) })
            })
        }
        val message = FlexibleMessage(
            role = "assistant",
            content = prior,
            imageUri = "content://scene/1",
        )
        val same = RpContinuation.withVersion(message, "She turns.", "content://scene/1")
        assertEquals("She turns.", MessageContent.text(same.content))
        assertEquals(jpeg, MessageContent.imageUrl(same.content))
        assertEquals("content://scene/1", same.imageUri)
        val other = RpContinuation.withVersion(message, "She waits.", "content://scene/2")
        assertEquals("She waits.", MessageContent.text(other.content))
        assertEquals("content://scene/2", other.imageUri)
        assertEquals(null, MessageContent.imageUrl(other.content))
        val none = RpContinuation.withVersion(message, "She speaks.", "")
        assertEquals("She speaks.", MessageContent.text(none.content))
        assertEquals(null, none.imageUri)
        assertEquals(null, MessageContent.imageUrl(none.content))
        val legacy = RpContinuation.withVersion(message, "She nods.", null)
        assertEquals(jpeg, MessageContent.imageUrl(legacy.content))
        assertEquals("content://scene/1", legacy.imageUri)
    }

    @Test fun aFinishedRussianOrFrenchLineStartsANewParagraph() {
        assertEquals("«Привет.»\n\nОна ждёт.", RpContinuation.join("«Привет.»", "Она ждёт."))
        assertEquals("« Bonjour. »\n\nElle attend.", RpContinuation.join("« Bonjour. »", "Elle attend."))
        assertEquals("Он улыбнулся.\n\nПотом обернулся.", RpContinuation.join("Он улыбнулся.", "Потом обернулся."))
    }

    @Test fun arabicAndIndicSentenceEndsStartANewParagraph() {
        assertEquals("مرحبا؟\n\nهي تنتظر.", RpContinuation.join("مرحبا؟", "هي تنتظر."))
        assertEquals("नमस्ते।\n\nवह प्रतीक्षा करती है।", RpContinuation.join("नमस्ते।", "वह प्रतीक्षा करती है।"))
    }

    @Test fun guillemetsHugLikeOtherClosers() {
        assertEquals("Да»", RpContinuation.join("Да", "»"))
        assertEquals("«Да", RpContinuation.join("«", "Да"))
    }

    @Test fun hebrewEthiopicGreekAndTibetanEndsStartANewParagraph() {
        assertEquals("שלום׃\n\nהיא מחכה.", RpContinuation.join("שלום׃", "היא מחכה."))
        assertEquals("ሰላም።\n\nእሷ ትጠብቃለች።", RpContinuation.join("ሰላም።", "እሷ ትጠብቃለች።"))
        assertEquals("Γεια σου;\n\nΠεριμένει.", RpContinuation.join("Γεια σου;", "Περιμένει."))
        assertEquals("བཀྲ་ཤིས།\n\nམོ་འགུགས།", RpContinuation.join("བཀྲ་ཤིས།", "མོ་འགུགས།"))
        assertEquals("សួស្តី។\n\nនាងរង់ចាំ។", RpContinuation.join("សួស្តី។", "នាងរង់ចាំ។"))
    }

    @Test fun hebrewQuoteTrailersStillCountAsFinished() {
        assertEquals("שלום׃״\n\nהיא מחכה.", RpContinuation.join("שלום׃״", "היא מחכה."))
    }

    @Test fun spanishInvertedMarksHugLikeOpeners() {
        assertEquals("¿Cómo", RpContinuation.join("¿", "Cómo"))
        assertEquals("¡Hola", RpContinuation.join("¡", "Hola"))
        assertEquals("Dijo: ¿Qué?", RpContinuation.join("Dijo: ¿", "Qué?"))
    }

    @Test fun armenianSyriacAndMongolianEndsStartANewParagraph() {
        assertEquals("Բարև՞\n\nՆա սպասում է։", RpContinuation.join("Բարև՞", "Նա սպասում է։"))
        assertEquals("Բարև՜\n\nՆա սպասում է։", RpContinuation.join("Բարև՜", "Նա սպասում է։"))
        assertEquals("ܫܠܡܐ܁\n\nܗܝ ܡܣܟܝܐ.", RpContinuation.join("ܫܠܡܐ܁", "ܗܝ ܡܣܟܝܐ."))
        assertEquals("Сайн᠃\n\nТэр хүлээнэ.", RpContinuation.join("Сайн᠃", "Тэр хүлээнэ."))
        assertEquals("Really‼\n\nShe waits.", RpContinuation.join("Really‼", "She waits."))
    }

    @Test fun unfinishedTibetanAndEthiopicDoNotGainASpace() {
        assertEquals("བཀྲ་ཤིསམོ", RpContinuation.join("བཀྲ་ཤིས", "མོ"))
        assertEquals("ሰላምእሷ", RpContinuation.join("ሰላም", "እሷ"))
    }

    @Test fun germanLowQuotesStillCountAsFinished() {
        assertEquals("„Hallo.“\n\nSie wartet.", RpContinuation.join("„Hallo.“", "Sie wartet."))
        // Trailing space the model already sent is kept (same as "She smiles. Then").
        assertEquals("„Hallo.“ Sie wartet.", RpContinuation.join("„Hallo.“ ", "Sie wartet."))
        assertEquals("„Hallo", RpContinuation.join("„", "Hallo"))
        assertEquals("She said “hello", RpContinuation.join("She said “", "hello"))
    }

    @Test fun fullwidthPeriodInterrobangAndMoreEndsStartANewParagraph() {
        assertEquals("彼女は微笑む．\n\nそして振り向く。", RpContinuation.join("彼女は微笑む．", "そして振り向く。"))
        assertEquals("Really‽\n\nShe waits.", RpContinuation.join("Really‽", "She waits."))
        assertEquals("გამარჯობა჻\n\nის ელოდება.", RpContinuation.join("გამარჯობა჻", "ის ელოდება."))
        assertEquals("Сайн᙮\n\nТэр хүлээнэ.", RpContinuation.join("Сайн᙮", "Тэр хүлээнэ."))
        assertEquals("සාදරයෙන්෴\n\nඇය බලා සිටී.", RpContinuation.join("සාදරයෙන්෴", "ඇය බලා සිටී."))
    }


    @Test fun swissGermanAndAngleQuotesStillCountAsFinished() {
        assertEquals("»Hallo.«\n\nSie wartet.", RpContinuation.join("»Hallo.«", "Sie wartet."))
        assertEquals("›Hallo.‹\n\nSie wartet.", RpContinuation.join("›Hallo.‹", "Sie wartet."))
        assertEquals("»Hallo", RpContinuation.join("»", "Hallo"))
        assertEquals("《来吧。》\n\n他等待。", RpContinuation.join("《来吧。》", "他等待。"))
        assertEquals("他说：《来", RpContinuation.join("他说：《", "来"))
    }

    @Test fun khmerThaiAndCopticEndsStartANewParagraph() {
        assertEquals("សួស្តី៕\n\nនាងរង់ចាំ។", RpContinuation.join("សួស្តី៕", "នាងរង់ចាំ។"))
        assertEquals("สวัสดีฯ\n\nเธอรออยู่", RpContinuation.join("สวัสดีฯ", "เธอรออยู่"))
        assertEquals("Ⲭⲉⲣⲉ⳹\n\nShe waits.", RpContinuation.join("Ⲭⲉⲣⲉ⳹", "She waits."))
    }

    @Test fun halfwidthAndPrimeQuotesStillCountAsFinished() {
        assertEquals("｢こんにちは。｣\n\n彼は待つ。", RpContinuation.join("｢こんにちは。｣", "彼は待つ。"))
        assertEquals("〝来て。〞\n\n他等待。", RpContinuation.join("〝来て。〞", "他等待。"))
        assertEquals("｢Hallo", RpContinuation.join("｢", "Hallo"))
        assertEquals("〝来", RpContinuation.join("〝", "来"))
        assertEquals("〟Hallo", RpContinuation.join("〟", "Hallo"))
    }

    @Test fun reversedLimbuLisuVaiAndHalfwidthEndsStartANewParagraph() {
        assertEquals("Really⸮\n\nShe waits.", RpContinuation.join("Really⸮", "She waits."))
        assertEquals("Hello᥄\n\nShe waits.", RpContinuation.join("Hello᥄", "She waits."))
        assertEquals("Hello᥅\n\nShe waits.", RpContinuation.join("Hello᥅", "She waits."))
        assertEquals("Hello꓿\n\nShe waits.", RpContinuation.join("Hello꓿", "She waits."))
        assertEquals("Hello꘎\n\nShe waits.", RpContinuation.join("Hello꘎", "She waits."))
        assertEquals("Hello꘏\n\nShe waits.", RpContinuation.join("Hello꘏", "She waits."))
        assertEquals("彼女は待つ｡\n\nそして", RpContinuation.join("彼女は待つ｡", "そして"))
        assertEquals("Really﹒\n\nShe waits.", RpContinuation.join("Really﹒", "She waits."))
    }

    @Test fun ornamentalAndVerticalQuotesStillCountAsFinished() {
        assertEquals("❝Hallo.❞\n\nShe waits.", RpContinuation.join("❝Hallo.❞", "She waits."))
        assertEquals("❛Hallo.❜\n\nShe waits.", RpContinuation.join("❛Hallo.❜", "She waits."))
        assertEquals("❝Hallo", RpContinuation.join("❝", "Hallo"))
        assertEquals("﹁来て。﹂\n\n他等待。", RpContinuation.join("﹁来て。﹂", "他等待。"))
        assertEquals("﹃来吧。﹄\n\n他等待。", RpContinuation.join("﹃来吧。﹄", "他等待。"))
        assertEquals("他说：﹁来", RpContinuation.join("他说：﹁", "来"))
    }

    @Test fun ethiopicArabicNkoAndMoreEndsStartANewParagraph() {
        assertEquals("ሰላም፧\n\nእሷ ትጠብቃለች።", RpContinuation.join("ሰላም፧", "እሷ ትጠብቃለች።"))
        assertEquals("ሰላም፨\n\nእሷ ትጠብቃለች።", RpContinuation.join("ሰላም፨", "እሷ ትጠብቃለች።"))
        assertEquals("مرحبا؛\n\nهي تنتظر.", RpContinuation.join("مرحبا؛", "هي تنتظر."))
        assertEquals("Hello߹\n\nShe waits.", RpContinuation.join("Hello߹", "She waits."))
        assertEquals("Hello᱾\n\nShe waits.", RpContinuation.join("Hello᱾", "She waits."))
        assertEquals("Hello᱿\n\nShe waits.", RpContinuation.join("Hello᱿", "She waits."))
        assertEquals("Hello꛳\n\nShe waits.", RpContinuation.join("Hello꛳", "She waits."))
        assertEquals("Hello꛷\n\nShe waits.", RpContinuation.join("Hello꛷", "She waits."))
        assertEquals("彼女は待つ︒\n\nそして", RpContinuation.join("彼女は待つ︒", "そして"))
        assertEquals("Really﹗\n\nShe waits.", RpContinuation.join("Really﹗", "She waits."))
        assertEquals("Really﹖\n\nShe waits.", RpContinuation.join("Really﹖", "She waits."))
    }


    @Test fun fullwidthHeavyAngleAndWhiteParenQuotesStillCountAsFinished() {
        assertEquals("＂Hallo.＂\n\nShe waits.", RpContinuation.join("＂Hallo.＂", "She waits."))
        assertEquals("＇Hallo.＇\n\nShe waits.", RpContinuation.join("＇Hallo.＇", "She waits."))
        assertEquals("❮Hallo.❯\n\nShe waits.", RpContinuation.join("❮Hallo.❯", "She waits."))
        assertEquals("❮Hallo", RpContinuation.join("❮", "Hallo"))
        assertEquals("｟Hallo.｠\n\nShe waits.", RpContinuation.join("｟Hallo.｠", "She waits."))
        assertEquals("｟Hallo", RpContinuation.join("｟", "Hallo"))
    }

    @Test fun verticalChamBalineseAndMoreEndsStartANewParagraph() {
        assertEquals("Really︖\n\nShe waits.", RpContinuation.join("Really︖", "She waits."))
        assertEquals("Really︕\n\nShe waits.", RpContinuation.join("Really︕", "She waits."))
        assertEquals("Hello꩝\n\nShe waits.", RpContinuation.join("Hello꩝", "She waits."))
        assertEquals("Hello꩞\n\nShe waits.", RpContinuation.join("Hello꩞", "She waits."))
        assertEquals("Hello꩟\n\nShe waits.", RpContinuation.join("Hello꩟", "She waits."))
        assertEquals("Hello᭞\n\nShe waits.", RpContinuation.join("Hello᭞", "She waits."))
        assertEquals("Hello᭟\n\nShe waits.", RpContinuation.join("Hello᭟", "She waits."))
        assertEquals("Сайн᠅\n\nТэр хүлээнэ.", RpContinuation.join("Сайн᠅", "Тэр хүлээнэ."))
        assertEquals("Hello᰻\n\nShe waits.", RpContinuation.join("Hello᰻", "She waits."))
        assertEquals("Hello᰼\n\nShe waits.", RpContinuation.join("Hello᰼", "She waits."))
    }

    @Test fun whiteTortoiseMathAngleAndHeavyOrnamentQuotesStillCountAsFinished() {
        assertEquals("〘Hallo.〙\n\nShe waits.", RpContinuation.join("〘Hallo.〙", "She waits."))
        assertEquals("〘Hallo", RpContinuation.join("〘", "Hallo"))
        assertEquals("⟨Hallo.⟩\n\nShe waits.", RpContinuation.join("⟨Hallo.⟩", "She waits."))
        assertEquals("⟨Hallo", RpContinuation.join("⟨", "Hallo"))
        assertEquals("❰Hallo.❱\n\nShe waits.", RpContinuation.join("❰Hallo.❱", "She waits."))
        assertEquals("❰Hallo", RpContinuation.join("❰", "Hallo"))
    }

    @Test fun tibetanMeeteiSaurashtraAndMoreEndsStartANewParagraph() {
        assertEquals("བཀྲ་ཤིས་༎\n\nམོ་འགུགས།", RpContinuation.join("བཀྲ་ཤིས་༎", "མོ་འགུགས།"))
        assertEquals("བཀྲ་ཤིས་༏\n\nམོ་འགུགས།", RpContinuation.join("བཀྲ་ཤིས་༏", "མོ་འགུགས།"))
        assertEquals("བཀྲ་ཤིས་༔\n\nམོ་འགུགས།", RpContinuation.join("བཀྲ་ཤིས་༔", "མོ་འགུགས།"))
        assertEquals("Hello꫰\n\nShe waits.", RpContinuation.join("Hello꫰", "She waits."))
        assertEquals("Hello꫱\n\nShe waits.", RpContinuation.join("Hello꫱", "She waits."))
        assertEquals("Hello꣎\n\nShe waits.", RpContinuation.join("Hello꣎", "She waits."))
        assertEquals("Hello꣏\n\nShe waits.", RpContinuation.join("Hello꣏", "She waits."))
        assertEquals("Halo꧉\n\nDia menunggu.", RpContinuation.join("Halo꧉", "Dia menunggu."))
        assertEquals("Hello꡶\n\nShe waits.", RpContinuation.join("Hello꡶", "She waits."))
        assertEquals("Hello꡷\n\nShe waits.", RpContinuation.join("Hello꡷", "She waits."))
        assertEquals("Hello꥟\n\nShe waits.", RpContinuation.join("Hello꥟", "She waits."))
        assertEquals("Hello᨞\n\nShe waits.", RpContinuation.join("Hello᨞", "She waits."))
        assertEquals("Hello᨟\n\nShe waits.", RpContinuation.join("Hello᨟", "She waits."))
    }


    @Test fun whiteSquareMathDoubleAndMathTortoiseQuotesStillCountAsFinished() {
        assertEquals("〚Hallo.〛\n\nShe waits.", RpContinuation.join("〚Hallo.〛", "She waits."))
        assertEquals("〚Hallo", RpContinuation.join("〚", "Hallo"))
        assertEquals("⟪Hallo.⟫\n\nShe waits.", RpContinuation.join("⟪Hallo.⟫", "She waits."))
        assertEquals("⟪Hallo", RpContinuation.join("⟪", "Hallo"))
        assertEquals("⟬Hallo.⟭\n\nShe waits.", RpContinuation.join("⟬Hallo.⟭", "She waits."))
        assertEquals("⟬Hallo", RpContinuation.join("⟬", "Hallo"))
    }

    @Test fun batakRunicMandaicAndMoreEndsStartANewParagraph() {
        assertEquals("Hello᯼\n\nShe waits.", RpContinuation.join("Hello᯼", "She waits."))
        assertEquals("Hello᯽\n\nShe waits.", RpContinuation.join("Hello᯽", "She waits."))
        assertEquals("Hello᯾\n\nShe waits.", RpContinuation.join("Hello᯾", "She waits."))
        assertEquals("Hello᯿\n\nShe waits.", RpContinuation.join("Hello᯿", "She waits."))
        assertEquals("Hello᛫\n\nShe waits.", RpContinuation.join("Hello᛫", "She waits."))
        assertEquals("Hello᛬\n\nShe waits.", RpContinuation.join("Hello᛬", "She waits."))
        assertEquals("Hello᛭\n\nShe waits.", RpContinuation.join("Hello᛭", "She waits."))
        assertEquals("Hello࡞\n\nShe waits.", RpContinuation.join("Hello࡞", "She waits."))
        assertEquals("Hello⵰\n\nShe waits.", RpContinuation.join("Hello⵰", "She waits."))
        assertEquals("Hello࠹\n\nShe waits.", RpContinuation.join("Hello࠹", "She waits."))
        assertEquals("Hello࠾\n\nShe waits.", RpContinuation.join("Hello࠾", "She waits."))
        assertEquals("Halo꧈\n\nDia menunggu.", RpContinuation.join("Halo꧈", "Dia menunggu."))
        assertEquals("Halo꧋\n\nDia menunggu.", RpContinuation.join("Halo꧋", "Dia menunggu."))
        assertEquals("Halo꧞\n\nDia menunggu.", RpContinuation.join("Halo꧞", "Dia menunggu."))
        assertEquals("Halo꧟\n\nDia menunggu.", RpContinuation.join("Halo꧟", "Dia menunggu."))
    }


    @Test fun mathWhiteSquareWhiteCurlyAndFlattenedParenQuotesStillCountAsFinished() {
        assertEquals("⟦Hallo.⟧\n\nShe waits.", RpContinuation.join("⟦Hallo.⟧", "She waits."))
        assertEquals("⟦Hallo", RpContinuation.join("⟦", "Hallo"))
        assertEquals("⦃Hallo.⦄\n\nShe waits.", RpContinuation.join("⦃Hallo.⦄", "She waits."))
        assertEquals("⦃Hallo", RpContinuation.join("⦃", "Hallo"))
        assertEquals("❨Hallo.❩\n\nShe waits.", RpContinuation.join("❨Hallo.❩", "She waits."))
        assertEquals("❨Hallo", RpContinuation.join("❨", "Hallo"))
    }

    @Test fun sundaneseTaiThamAndKayahLiEndsStartANewParagraph() {
        assertEquals("Hello᳀\n\nShe waits.", RpContinuation.join("Hello᳀", "She waits."))
        assertEquals("Hello᳁\n\nShe waits.", RpContinuation.join("Hello᳁", "She waits."))
        assertEquals("Hello᳂\n\nShe waits.", RpContinuation.join("Hello᳂", "She waits."))
        assertEquals("Hello᳃\n\nShe waits.", RpContinuation.join("Hello᳃", "She waits."))
        assertEquals("Hello᳄\n\nShe waits.", RpContinuation.join("Hello᳄", "She waits."))
        assertEquals("Hello᳅\n\nShe waits.", RpContinuation.join("Hello᳅", "She waits."))
        assertEquals("Hello᳆\n\nShe waits.", RpContinuation.join("Hello᳆", "She waits."))
        assertEquals("Hello᳇\n\nShe waits.", RpContinuation.join("Hello᳇", "She waits."))
        assertEquals("Hello᪨\n\nShe waits.", RpContinuation.join("Hello᪨", "She waits."))
        assertEquals("Hello᪩\n\nShe waits.", RpContinuation.join("Hello᪩", "She waits."))
        assertEquals("Hello᪪\n\nShe waits.", RpContinuation.join("Hello᪪", "She waits."))
        assertEquals("Hello᪫\n\nShe waits.", RpContinuation.join("Hello᪫", "She waits."))
        assertEquals("Hello᪬\n\nShe waits.", RpContinuation.join("Hello᪬", "She waits."))
        assertEquals("Hello᪭\n\nShe waits.", RpContinuation.join("Hello᪭", "She waits."))
        assertEquals("Hello꤮\n\nShe waits.", RpContinuation.join("Hello꤮", "She waits."))
        assertEquals("Hello꤯\n\nShe waits.", RpContinuation.join("Hello꤯", "She waits."))
    }

    @Test fun mediumFlattenedMediumAngleAndLightTortoiseQuotesStillCountAsFinished() {
        assertEquals("❪Hallo.❫\n\nShe waits.", RpContinuation.join("❪Hallo.❫", "She waits."))
        assertEquals("❪Hallo", RpContinuation.join("❪", "Hallo"))
        assertEquals("❬Hallo.❭\n\nShe waits.", RpContinuation.join("❬Hallo.❭", "She waits."))
        assertEquals("❬Hallo", RpContinuation.join("❬", "Hallo"))
        assertEquals("❲Hallo.❳\n\nShe waits.", RpContinuation.join("❲Hallo.❳", "She waits."))
        assertEquals("❲Hallo", RpContinuation.join("❲", "Hallo"))
    }

    @Test fun hanunooThaiKhmerTibetanCopticAndMongolianEndsStartANewParagraph() {
        assertEquals("Hello᜵\n\nShe waits.", RpContinuation.join("Hello᜵", "She waits."))
        assertEquals("Hello᜶\n\nShe waits.", RpContinuation.join("Hello᜶", "She waits."))
        assertEquals("Hello๚\n\nShe waits.", RpContinuation.join("Hello๚", "She waits."))
        assertEquals("Hello๛\n\nShe waits.", RpContinuation.join("Hello๛", "She waits."))
        assertEquals("Hello៚\n\nShe waits.", RpContinuation.join("Hello៚", "She waits."))
        assertEquals("Hello༑\n\nShe waits.", RpContinuation.join("Hello༑", "She waits."))
        assertEquals("Hello༒\n\nShe waits.", RpContinuation.join("Hello༒", "She waits."))
        assertEquals("Hello⳺\n\nShe waits.", RpContinuation.join("Hello⳺", "She waits."))
        assertEquals("Hello⳻\n\nShe waits.", RpContinuation.join("Hello⳻", "She waits."))
        assertEquals("Hello⳼\n\nShe waits.", RpContinuation.join("Hello⳼", "She waits."))
        assertEquals("Hello⳽\n\nShe waits.", RpContinuation.join("Hello⳽", "She waits."))
        assertEquals("Hello᠉\n\nShe waits.", RpContinuation.join("Hello᠉", "She waits."))
    }

    @Test fun mediumCurlyWhiteParenAndBlackTortoiseQuotesStillCountAsFinished() {
        assertEquals("❴Hallo.❵\n\nShe waits.", RpContinuation.join("❴Hallo.❵", "She waits."))
        assertEquals("❴Hallo", RpContinuation.join("❴", "Hallo"))
        assertEquals("⦅Hallo.⦆\n\nShe waits.", RpContinuation.join("⦅Hallo.⦆", "She waits."))
        assertEquals("⦅Hallo", RpContinuation.join("⦅", "Hallo"))
        assertEquals("⦗Hallo.⦘\n\nShe waits.", RpContinuation.join("⦗Hallo.⦘", "She waits."))
        assertEquals("⦗Hallo", RpContinuation.join("⦗", "Hallo"))
    }

    @Test fun meeteiBamumKhmerMongolianTibetanBalineseAndJavaneseEndsStartANewParagraph() {
        assertEquals("Hello꯫\n\nShe waits.", RpContinuation.join("Hello꯫", "She waits."))
        assertEquals("Hello꛲\n\nShe waits.", RpContinuation.join("Hello꛲", "She waits."))
        assertEquals("Hello꛴\n\nShe waits.", RpContinuation.join("Hello꛴", "She waits."))
        assertEquals("Hello꛵\n\nShe waits.", RpContinuation.join("Hello꛵", "She waits."))
        assertEquals("Hello꛶\n\nShe waits.", RpContinuation.join("Hello꛶", "She waits."))
        assertEquals("Hello៖\n\nShe waits.", RpContinuation.join("Hello៖", "She waits."))
        assertEquals("Hello៙\n\nShe waits.", RpContinuation.join("Hello៙", "She waits."))
        assertEquals("Hello᠀\n\nShe waits.", RpContinuation.join("Hello᠀", "She waits."))
        assertEquals("Hello᠁\n\nShe waits.", RpContinuation.join("Hello᠁", "She waits."))
        assertEquals("Hello᠆\n\nShe waits.", RpContinuation.join("Hello᠆", "She waits."))
        assertEquals("Hello༈\n\nShe waits.", RpContinuation.join("Hello༈", "She waits."))
        assertEquals("Hello᭚\n\nShe waits.", RpContinuation.join("Hello᭚", "She waits."))
        assertEquals("Hello᭛\n\nShe waits.", RpContinuation.join("Hello᭛", "She waits."))
        assertEquals("Hello꧌\n\nShe waits.", RpContinuation.join("Hello꧌", "She waits."))
        assertEquals("Hello꧍\n\nShe waits.", RpContinuation.join("Hello꧍", "She waits."))
    }


    @Test fun zImageZBindingAndCurledAngleQuotesStillCountAsFinished() {
        assertEquals("⦇Hallo.⦈\n\nShe waits.", RpContinuation.join("⦇Hallo.⦈", "She waits."))
        assertEquals("⦇Hallo", RpContinuation.join("⦇", "Hallo"))
        assertEquals("⦉Hallo.⦊\n\nShe waits.", RpContinuation.join("⦉Hallo.⦊", "She waits."))
        assertEquals("⦉Hallo", RpContinuation.join("⦉", "Hallo"))
        assertEquals("⧼Hallo.⧽\n\nShe waits.", RpContinuation.join("⧼Hallo.⧽", "She waits."))
        assertEquals("⧼Hallo", RpContinuation.join("⧼", "Hallo"))
    }

    @Test fun sylotiTibetanMongolianBalineseAndStenographicEndsStartANewParagraph() {
        assertEquals("Hello꠨\n\nShe waits.", RpContinuation.join("Hello꠨", "She waits."))
        assertEquals("Hello꠩\n\nShe waits.", RpContinuation.join("Hello꠩", "She waits."))
        assertEquals("Hello꠪\n\nShe waits.", RpContinuation.join("Hello꠪", "She waits."))
        assertEquals("Hello꠫\n\nShe waits.", RpContinuation.join("Hello꠫", "She waits."))
        assertEquals("Hello༉\n\nShe waits.", RpContinuation.join("Hello༉", "She waits."))
        assertEquals("Hello༊\n\nShe waits.", RpContinuation.join("Hello༊", "She waits."))
        assertEquals("Hello༐\n\nShe waits.", RpContinuation.join("Hello༐", "She waits."))
        assertEquals("Hello༴\n\nShe waits.", RpContinuation.join("Hello༴", "She waits."))
        assertEquals("Hello᠂\n\nShe waits.", RpContinuation.join("Hello᠂", "She waits."))
        assertEquals("Hello᠄\n\nShe waits.", RpContinuation.join("Hello᠄", "She waits."))
        assertEquals("Hello᠇\n\nShe waits.", RpContinuation.join("Hello᠇", "She waits."))
        assertEquals("Hello᭜\n\nShe waits.", RpContinuation.join("Hello᭜", "She waits."))
        assertEquals("Hello᭝\n\nShe waits.", RpContinuation.join("Hello᭝", "She waits."))
        assertEquals("Hello᭠\n\nShe waits.", RpContinuation.join("Hello᭠", "She waits."))
        assertEquals("Hello⸼\n\nShe waits.", RpContinuation.join("Hello⸼", "She waits."))
        assertEquals("Hello⸽\n\nShe waits.", RpContinuation.join("Hello⸽", "She waits."))
    }

    @Test fun theContinueDirectionAsksForAnExactSeam() {
        val d = RpPromptEngine.CONTINUE_DIRECTION
        assert("exactly where it ends" in d)
        assert("mid-sentence" in d)
    }

    @Test
    fun continueKeepsAltsWhenTheBubbleDidNotGrow() {
        assertFalse(RpContinuation.continueDroppedAlts("She waits.", "She waits."))
        assertFalse(RpContinuation.continueDroppedAlts("She waits.", ""))
    }

    @Test
    fun continueDropsAltsWhenTheBubbleGrew() {
        assertTrue(RpContinuation.continueDroppedAlts("She waits.", "She waits.\n\nHe nods."))
    }
}
