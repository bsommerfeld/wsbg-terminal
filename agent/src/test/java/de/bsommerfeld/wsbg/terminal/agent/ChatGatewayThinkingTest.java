package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins {@link ChatGateway#stripThinking(String)} — the net under a reply whose
 * chain-of-thought came back inside the content. Granite 4.2 has thinking ON by
 * default (ollama.com/library/granite4.2) and the managed path switches it off,
 * but the "openai" endpoint mode has no such switch: there the {@code <think>}
 * block arrives in the text, and unstripped it would be read as the headline.
 */
class ChatGatewayThinkingTest {

    @Test
    void stripsALeadingThinkBlockAndKeepsTheAnswer() {
        assertEquals("{\"headline\":\"Nvidia\"}",
                ChatGateway.stripThinking("<think>\nLet me weigh the evidence.\n</think>\n"
                        + "{\"headline\":\"Nvidia\"}"));
    }

    @Test
    void leavesAnOrdinaryReplyExactlyAsItCame() {
        String plain = "  {\"headline\":\"Nvidia\"}  ";
        assertEquals(plain, ChatGateway.stripThinking(plain));
        assertEquals("", ChatGateway.stripThinking(null));
    }

    @Test
    void anUnclosedThinkBlockIsAWhiff_notHalfAnAnswer() {
        // numPredict ran out mid-reasoning: there is no answer behind the block,
        // and "" is what every caller already handles as a whiff.
        assertEquals("", ChatGateway.stripThinking("<think>still reasoning about the cluster"));
    }

    @Test
    void doesNotEatAThinkBlockThatIsNotTheOpening() {
        // Only a reply the model OPENS with is reasoning; a mention further in is content.
        String s = "Der Kurs faellt. <think>hm</think> Ende";
        assertEquals(s, ChatGateway.stripThinking(s));
    }
}
