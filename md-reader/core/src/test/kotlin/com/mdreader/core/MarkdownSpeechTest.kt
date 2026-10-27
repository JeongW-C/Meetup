package com.mdreader.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownSpeechTest {

    private fun read(md: String) = MarkdownSpeech.toParagraphs(md.trimIndent())

    @Test
    fun headingsBecomeSentences() {
        assertEquals(
            listOf("제목입니다.", "소제목?", "본문입니다."),
            read(
                """
                # 제목입니다
                ## 소제목? ##
                본문입니다.
                """
            ),
        )
    }

    @Test
    fun setextHeadings() {
        assertEquals(
            listOf("큰 제목.", "작은 제목.", "본문"),
            read(
                """
                큰 제목
                ======
                작은 제목
                ---
                본문
                """
            ),
        )
    }

    @Test
    fun emphasisAndStrikethroughAreRemoved() {
        assertEquals(
            listOf("굵게 그리고 기울임 그리고 둘 다 취소선 밑줄"),
            read("**굵게** 그리고 *기울임* 그리고 ***둘 다*** ~~취소선~~ __밑줄__"),
        )
    }

    @Test
    fun snakeCaseIsKept() {
        assertEquals(listOf("변수 my_var_name 확인"), read("변수 my_var_name 확인"))
    }

    @Test
    fun linksKeepTextAndImagesAndUrlsAreDropped() {
        assertEquals(
            listOf("공식 문서 참고 참조 링크 끝"),
            read("[공식 문서](https://example.com) 참고 ![그림](a.png) [참조 링크][1] https://example.com/path 끝\n\n[1]: https://example.com"),
        )
    }

    @Test
    fun fencedCodeBlocksAreSkippedButInlineCodeIsRead() {
        assertEquals(
            listOf("앞 문단", "명령어 ls -a 실행", "뒤 문단"),
            read(
                """
                앞 문단

                ```kotlin
                val x = 1
                ```

                명령어 `ls -a` 실행

                ~~~
                skipped
                ~~~
                뒤 문단
                """
            ),
        )
    }

    @Test
    fun listItemsAreSeparateParagraphs() {
        assertEquals(
            listOf("사과", "바나나 계속되는 줄", "첫째", "완료된 일", "중첩 항목"),
            read(
                """
                - 사과
                * 바나나
                  계속되는 줄
                1. 첫째
                - [x] 완료된 일
                    - 중첩 항목
                """
            ),
        )
    }

    @Test
    fun blockquotesAreRead() {
        assertEquals(listOf("인용된 문장 이어지는 인용"), read("> 인용된 문장\n> 이어지는 인용"))
    }

    @Test
    fun tablesAreReadRowByRow() {
        assertEquals(
            listOf("이름, 나이", "철수, 10", "영희, 12"),
            read(
                """
                | 이름 | 나이 |
                |:-----|----:|
                | 철수 | 10 |
                | **영희** | 12 |
                """
            ),
        )
    }

    @Test
    fun htmlAndCommentsAreStripped() {
        assertEquals(
            listOf("빨간 글씨", "보이는 문장"),
            read(
                """
                <span style="color:red">빨간</span> 글씨<!-- 숨김 -->

                <!--
                여러 줄 주석
                -->
                보이는 문장
                """
            ),
        )
    }

    @Test
    fun frontMatterAndHorizontalRulesAreSkipped() {
        assertEquals(
            listOf("본문", "다음"),
            read(
                """
                ---
                title: 테스트
                ---
                본문

                ***
                다음
                """
            ),
        )
    }

    @Test
    fun escapedCharactersStayLiteral() {
        assertEquals(listOf("별표 *는 그대로, # 기호도"), read("별표 \\*는 그대로, \\# 기호도"))
    }

    @Test
    fun entitiesAreDecoded() {
        assertEquals(listOf("A & B < C"), read("A &amp; B &lt; C"))
    }

    @Test
    fun hashtagWithoutSpaceIsNotAHeading() {
        assertEquals(listOf("#태그 문장"), read("#태그 문장"))
    }

    @Test
    fun emptyInputGivesNothing() {
        assertEquals(emptyList(), read(""))
        assertEquals(emptyList(), read("```\ncode only\n```"))
    }
}
