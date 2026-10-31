package com.studyflow.notebook;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WikiLinksTest {
    @Test void onlyProseLinksAreExtractedAndAliasesAnchorsPreserved() {
        var links=new WikiLinks().parse("[[概率#定义|概率论]] `[[inline]]`\n```md\n[[code]]\n```\n[[note:123|项目]]\\[[escaped]]");
        assertEquals(2,links.size());assertEquals("定义",links.getFirst().anchor());assertEquals("概率论",links.getFirst().label());assertEquals("note:123",links.getLast().target());
    }
    @Test void normalizedNamesRemainConsistent(){assertEquals(WikiLinks.key("ＡＢＣ"),WikiLinks.key("abc"));}
}
