package de.bsommerfeld.wsbg.orb;

import javafx.scene.paint.Color;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static de.bsommerfeld.wsbg.orb.GLFunctions.*;

/**
 * Renders one of the storm shaders - the orb's marble ({@code orb.frag}) or the
 * flat sheet ({@code sheet.frag}) - into an offscreen framebuffer of its own GL
 * context and reads the frame back into a native buffer that JavaFX shows
 * without a copy (premultiplied BGRA, top row first). Both shaders take the same
 * uniforms; one that a shader does not declare is simply not set.
 * <p>
 * Every call must come from the same thread - {@link MarbleOrb} and
 * {@link MarbleSheet} use the JavaFX application thread, which JavaFX's own
 * renderer never draws on.
 */
final class OrbRenderer implements AutoCloseable {

    private static final int NOISE_SIZE = 256;

    private final GL context;
    private final GLFunctions gl;
    private final int program;
    private final int vertexArray;
    private final int noise;
    private final Arena arena = Arena.ofShared();

    private int framebuffer;
    private int renderbuffer;
    private int width;
    private int height;
    private MemorySegment pixels;
    /** The sheet's distance field, on texture unit 1; 0 until one is given. */
    private int distance;
    private float reach;

    /** @param fragmentShader the shader's resource name next to this class */
    OrbRenderer(String fragmentShader) {
        context = GL.create();
        context.makeCurrent();
        gl = new GLFunctions(context);
        program = gl.program(resource("orb.vert"), resource(fragmentShader));
        vertexArray = gl.genVertexArray();
        noise = uploadNoise();
    }

    /** (Re)allocates the target for the given size in device pixels and returns the buffer frames land in. */
    ByteBuffer resize(int width, int height) {
        context.makeCurrent();
        if (framebuffer != 0) {
            gl.deleteFramebuffer(framebuffer);
            gl.deleteRenderbuffer(renderbuffer);
        }
        this.width = width;
        this.height = height;

        renderbuffer = gl.genRenderbuffer();
        gl.bindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
        gl.renderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, width, height);
        framebuffer = gl.genFramebuffer();
        gl.bindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        gl.framebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, renderbuffer);
        if (gl.checkFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Offscreen framebuffer incomplete");
        }

        pixels = arena.allocate((long) width * height * 4);
        return pixels.asByteBuffer();
    }

    /**
     * Draws one frame into the buffer from {@link #resize}.
     *
     * @param time  storm time in seconds - where the storm inside stands
     * @param hover 0 at rest (the plain SVG ring, an empty sheet), 1 fully revealed
     * @param ring  the colour of the ring at rest
     */
    void render(float time, float hover, OrbPalette palette, Color ring) {
        render(time, hover, palette, ring, 0);
    }

    /** @param mode the sheet's mode: 0 pours in, 1 spreads as a wave (the orb has none) */
    void render(float time, float hover, OrbPalette palette, Color ring, float mode) {
        context.makeCurrent();
        gl.bindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        gl.viewport(0, 0, width, height);

        gl.useProgram(program);
        gl.activeTexture(GL_TEXTURE0);
        gl.bindTexture(GL_TEXTURE_2D, noise);
        gl.uniform1i(gl.uniformLocation(program, "uNoise"), 0);
        if (distance != 0) {
            gl.activeTexture(GL_TEXTURE0 + 1);
            gl.bindTexture(GL_TEXTURE_2D, distance);
            gl.uniform1i(gl.uniformLocation(program, "uDistance"), 1);
            gl.uniform1f(gl.uniformLocation(program, "uReach"), reach);
            gl.activeTexture(GL_TEXTURE0);
        }
        gl.uniform2f(gl.uniformLocation(program, "uRes"), width, height);
        gl.uniform1f(gl.uniformLocation(program, "uTime"), time);
        gl.uniform1f(gl.uniformLocation(program, "uHover"), hover);
        gl.uniform1f(gl.uniformLocation(program, "uMode"), mode);
        Color[] stops = palette.stops();
        for (int i = 0; i < stops.length; i++) {
            color(gl.uniformLocation(program, "uPal[" + i + "]"), stops[i]);
        }
        color(gl.uniformLocation(program, "uCloud"), palette.cloud());
        color(gl.uniformLocation(program, "uLine"), ring);

        gl.bindVertexArray(vertexArray);
        gl.drawArrays(GL_TRIANGLES, 0, 3);

        gl.pixelStorei(GL_PACK_ALIGNMENT, 4);
        gl.readPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
    }

    /**
     * Hands the sheet shader a distance field: one float per cell, row by row
     * from the top, stretched over the whole target.
     *
     * @param reach the largest distance in the field - how far the wave runs
     */
    void distance(float[] field, int width, int height, float reach) {
        context.makeCurrent();
        if (distance == 0) {
            distance = gl.genTexture();
        }
        this.reach = reach;
        gl.bindTexture(GL_TEXTURE_2D, distance);
        gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        gl.pixelStorei(GL_UNPACK_ALIGNMENT, 4);
        try (Arena upload = Arena.ofConfined()) {
            MemorySegment staging = upload.allocateFrom(ValueLayout.JAVA_FLOAT, field);
            gl.texImage2D(GL_TEXTURE_2D, 0, GL_R32F, width, height, GL_RED, GL_FLOAT, staging);
        }
    }

    private void color(int location, Color color) {
        gl.uniform3f(location, (float) color.getRed(), (float) color.getGreen(), (float) color.getBlue());
    }

    /** The four noise fields (base, swirl u/v, clouds) as a 16-bit RGBA texture - 8 bit shows steps in the storm. */
    private int uploadNoise() {
        byte[] data;
        try (InputStream in = OrbRenderer.class.getResourceAsStream("orb-noise.rgba16")) {
            data = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int texture = gl.genTexture();
        gl.bindTexture(GL_TEXTURE_2D, texture);
        gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        gl.pixelStorei(GL_UNPACK_ALIGNMENT, 2);
        try (Arena upload = Arena.ofConfined()) {
            MemorySegment staging = upload.allocate(data.length);
            staging.copyFrom(MemorySegment.ofArray(data));
            gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA16, NOISE_SIZE, NOISE_SIZE, GL_RGBA, GL_UNSIGNED_SHORT, staging);
        }
        return texture;
    }

    private static String resource(String name) {
        try (InputStream in = OrbRenderer.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        context.makeCurrent();
        if (framebuffer != 0) {
            gl.deleteFramebuffer(framebuffer);
            gl.deleteRenderbuffer(renderbuffer);
        }
        gl.deleteTexture(noise);
        if (distance != 0) {
            gl.deleteTexture(distance);
        }
        gl.deleteVertexArray(vertexArray);
        gl.deleteProgram(program);
        context.close();
        arena.close();
    }
}
