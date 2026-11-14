package org.dflib.jjava.jupyter.kernel.display.common;

import org.dflib.jjava.jupyter.kernel.display.DisplayData;
import org.dflib.jjava.jupyter.kernel.display.Renderer;
import org.dflib.jjava.jupyter.kernel.display.mime.MIMEType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.CharBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TextTest {

    private Renderer renderer;

    @BeforeEach
    public void setUp() {
        this.renderer = new Renderer();
        Text.registerAll(this.renderer);
    }

    @Test
    public void rendersStringAsIs() {
        DisplayData data = this.renderer.render("hello");

        assertEquals("hello", data.getData(MIMEType.TEXT_PLAIN));
    }

    @Test
    public void materializesStringBuilder() {
        DisplayData data = this.renderer.render(new StringBuilder("hello"));

        assertEquals("hello", data.getData(MIMEType.TEXT_PLAIN));
    }

    @Test
    public void materializesCharBuffer() {
        // CharBuffer is a CharSequence whose implementation classes Gson cannot
        // serialize reflectively on modern JDKs (JsonIOException)
        DisplayData data = this.renderer.render(CharBuffer.wrap("hello"));

        assertEquals("hello", data.getData(MIMEType.TEXT_PLAIN));
    }

    @Test
    public void materializesCustomCharSequence() {
        DisplayData data = this.renderer.render(new CustomText("hello"));

        assertEquals("hello", data.getData(MIMEType.TEXT_PLAIN));
    }

    @Test
    public void materializesForAlternateMimeTypes() {
        DisplayData data = this.renderer.renderAs(new CustomText("<b>hi</b>"), MIMEType.TEXT_HTML.toString());

        assertEquals("<b>hi</b>", data.getData(MIMEType.TEXT_HTML));
    }

    /**
     * Stand-in for library-provided CharSequence implementations (e.g.
     * commons-text TextStringBuilder, Groovy GString) that Gson would
     * otherwise serialize as a reflective field dump.
     */
    private static class CustomText implements CharSequence {
        private final String value;

        CustomText(String value) {
            this.value = value;
        }

        @Override
        public int length() {
            return value.length();
        }

        @Override
        public char charAt(int index) {
            return value.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return value.subSequence(start, end);
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
