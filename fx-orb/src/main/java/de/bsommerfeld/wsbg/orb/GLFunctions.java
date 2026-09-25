package de.bsommerfeld.wsbg.orb;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static de.bsommerfeld.wsbg.orb.Native.call;
import static de.bsommerfeld.wsbg.orb.Native.downcall;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The slice of OpenGL the orb needs, the same on every platform. The entry points
 * come from the {@link GL} context they are bound for, so an instance belongs to
 * that one context and must only be used while it is current.
 */
final class GLFunctions {

    static final int GL_TEXTURE_2D = 0x0DE1;
    static final int GL_TEXTURE0 = 0x84C0;
    static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    static final int GL_TEXTURE_WRAP_S = 0x2802;
    static final int GL_TEXTURE_WRAP_T = 0x2803;
    static final int GL_LINEAR = 0x2601;
    static final int GL_REPEAT = 0x2901;
    static final int GL_RGBA = 0x1908;
    static final int GL_RGBA8 = 0x8058;
    static final int GL_RGBA16 = 0x805B;
    static final int GL_BGRA = 0x80E1;
    static final int GL_UNSIGNED_SHORT = 0x1403;
    static final int GL_R32F = 0x822E;
    static final int GL_RED = 0x1903;
    static final int GL_FLOAT = 0x1406;
    static final int GL_CLAMP_TO_EDGE = 0x812F;
    static final int GL_UNSIGNED_INT_8_8_8_8_REV = 0x8367;
    static final int GL_FRAMEBUFFER = 0x8D40;
    static final int GL_RENDERBUFFER = 0x8D41;
    static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    static final int GL_VERTEX_SHADER = 0x8B31;
    static final int GL_FRAGMENT_SHADER = 0x8B30;
    static final int GL_COMPILE_STATUS = 0x8B81;
    static final int GL_LINK_STATUS = 0x8B82;
    static final int GL_TRIANGLES = 0x0004;
    static final int GL_PACK_ALIGNMENT = 0x0D05;
    static final int GL_UNPACK_ALIGNMENT = 0x0CF5;

    private final MethodHandle glGenFramebuffers;
    private final MethodHandle glBindFramebuffer;
    private final MethodHandle glDeleteFramebuffers;
    private final MethodHandle glCheckFramebufferStatus;
    private final MethodHandle glGenRenderbuffers;
    private final MethodHandle glBindRenderbuffer;
    private final MethodHandle glRenderbufferStorage;
    private final MethodHandle glFramebufferRenderbuffer;
    private final MethodHandle glDeleteRenderbuffers;
    private final MethodHandle glViewport;
    private final MethodHandle glCreateShader;
    private final MethodHandle glShaderSource;
    private final MethodHandle glCompileShader;
    private final MethodHandle glGetShaderiv;
    private final MethodHandle glGetShaderInfoLog;
    private final MethodHandle glDeleteShader;
    private final MethodHandle glCreateProgram;
    private final MethodHandle glAttachShader;
    private final MethodHandle glLinkProgram;
    private final MethodHandle glGetProgramiv;
    private final MethodHandle glGetProgramInfoLog;
    private final MethodHandle glUseProgram;
    private final MethodHandle glDeleteProgram;
    private final MethodHandle glGetUniformLocation;
    private final MethodHandle glUniform1i;
    private final MethodHandle glUniform1f;
    private final MethodHandle glUniform2f;
    private final MethodHandle glUniform3f;
    private final MethodHandle glGenTextures;
    private final MethodHandle glBindTexture;
    private final MethodHandle glActiveTexture;
    private final MethodHandle glTexImage2D;
    private final MethodHandle glTexParameteri;
    private final MethodHandle glDeleteTextures;
    private final MethodHandle glGenVertexArrays;
    private final MethodHandle glBindVertexArray;
    private final MethodHandle glDeleteVertexArrays;
    private final MethodHandle glDrawArrays;
    private final MethodHandle glPixelStorei;
    private final MethodHandle glReadPixels;

    /** Binds every function for the context, which must be current. */
    GLFunctions(GL context) {
        glGenFramebuffers = function(context, "glGenFramebuffers", null, JAVA_INT, ADDRESS);
        glBindFramebuffer = function(context, "glBindFramebuffer", null, JAVA_INT, JAVA_INT);
        glDeleteFramebuffers = function(context, "glDeleteFramebuffers", null, JAVA_INT, ADDRESS);
        glCheckFramebufferStatus = function(context, "glCheckFramebufferStatus", JAVA_INT, JAVA_INT);
        glGenRenderbuffers = function(context, "glGenRenderbuffers", null, JAVA_INT, ADDRESS);
        glBindRenderbuffer = function(context, "glBindRenderbuffer", null, JAVA_INT, JAVA_INT);
        glRenderbufferStorage = function(context, "glRenderbufferStorage", null, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
        glFramebufferRenderbuffer = function(context, "glFramebufferRenderbuffer", null, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
        glDeleteRenderbuffers = function(context, "glDeleteRenderbuffers", null, JAVA_INT, ADDRESS);
        glViewport = function(context, "glViewport", null, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
        glCreateShader = function(context, "glCreateShader", JAVA_INT, JAVA_INT);
        glShaderSource = function(context, "glShaderSource", null, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
        glCompileShader = function(context, "glCompileShader", null, JAVA_INT);
        glGetShaderiv = function(context, "glGetShaderiv", null, JAVA_INT, JAVA_INT, ADDRESS);
        glGetShaderInfoLog = function(context, "glGetShaderInfoLog", null, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
        glDeleteShader = function(context, "glDeleteShader", null, JAVA_INT);
        glCreateProgram = function(context, "glCreateProgram", JAVA_INT);
        glAttachShader = function(context, "glAttachShader", null, JAVA_INT, JAVA_INT);
        glLinkProgram = function(context, "glLinkProgram", null, JAVA_INT);
        glGetProgramiv = function(context, "glGetProgramiv", null, JAVA_INT, JAVA_INT, ADDRESS);
        glGetProgramInfoLog = function(context, "glGetProgramInfoLog", null, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
        glUseProgram = function(context, "glUseProgram", null, JAVA_INT);
        glDeleteProgram = function(context, "glDeleteProgram", null, JAVA_INT);
        glGetUniformLocation = function(context, "glGetUniformLocation", JAVA_INT, JAVA_INT, ADDRESS);
        glUniform1i = function(context, "glUniform1i", null, JAVA_INT, JAVA_INT);
        glUniform1f = function(context, "glUniform1f", null, JAVA_INT, JAVA_FLOAT);
        glUniform2f = function(context, "glUniform2f", null, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT);
        glUniform3f = function(context, "glUniform3f", null, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);
        glGenTextures = function(context, "glGenTextures", null, JAVA_INT, ADDRESS);
        glBindTexture = function(context, "glBindTexture", null, JAVA_INT, JAVA_INT);
        glActiveTexture = function(context, "glActiveTexture", null, JAVA_INT);
        glTexImage2D = function(context, "glTexImage2D", null,
                JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);
        glTexParameteri = function(context, "glTexParameteri", null, JAVA_INT, JAVA_INT, JAVA_INT);
        glDeleteTextures = function(context, "glDeleteTextures", null, JAVA_INT, ADDRESS);
        glGenVertexArrays = function(context, "glGenVertexArrays", null, JAVA_INT, ADDRESS);
        glBindVertexArray = function(context, "glBindVertexArray", null, JAVA_INT);
        glDeleteVertexArrays = function(context, "glDeleteVertexArrays", null, JAVA_INT, ADDRESS);
        glDrawArrays = function(context, "glDrawArrays", null, JAVA_INT, JAVA_INT, JAVA_INT);
        glPixelStorei = function(context, "glPixelStorei", null, JAVA_INT, JAVA_INT);
        glReadPixels = function(context, "glReadPixels", null,
                JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);
    }

    private static MethodHandle function(GL context, String name, MemoryLayout result, MemoryLayout... args) {
        return downcall(context.function(name), result, args);
    }

    // --- objects ----------------------------------------------------------------

    private int gen(MethodHandle generator) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocate(JAVA_INT);
            call(generator, 1, name);
            return name.get(JAVA_INT, 0);
        }
    }

    private void delete(MethodHandle deleter, int name) {
        try (Arena arena = Arena.ofConfined()) {
            call(deleter, 1, arena.allocateFrom(JAVA_INT, name));
        }
    }

    int genFramebuffer() { return gen(glGenFramebuffers); }
    int genRenderbuffer() { return gen(glGenRenderbuffers); }
    int genTexture() { return gen(glGenTextures); }
    int genVertexArray() { return gen(glGenVertexArrays); }

    void deleteFramebuffer(int name) { delete(glDeleteFramebuffers, name); }
    void deleteRenderbuffer(int name) { delete(glDeleteRenderbuffers, name); }
    void deleteTexture(int name) { delete(glDeleteTextures, name); }
    void deleteVertexArray(int name) { delete(glDeleteVertexArrays, name); }

    void bindFramebuffer(int target, int name) { call(glBindFramebuffer, target, name); }
    void bindRenderbuffer(int target, int name) { call(glBindRenderbuffer, target, name); }
    void renderbufferStorage(int target, int format, int width, int height) {
        call(glRenderbufferStorage, target, format, width, height);
    }
    void framebufferRenderbuffer(int target, int attachment, int rbTarget, int name) {
        call(glFramebufferRenderbuffer, target, attachment, rbTarget, name);
    }
    int checkFramebufferStatus(int target) { return (int) call(glCheckFramebufferStatus, target); }

    void bindTexture(int target, int name) { call(glBindTexture, target, name); }
    void activeTexture(int unit) { call(glActiveTexture, unit); }
    void texParameteri(int target, int name, int value) { call(glTexParameteri, target, name, value); }
    void texImage2D(int target, int level, int internalFormat, int width, int height,
                    int format, int type, MemorySegment pixels) {
        call(glTexImage2D, target, level, internalFormat, width, height, 0, format, type, pixels);
    }

    void bindVertexArray(int name) { call(glBindVertexArray, name); }
    void viewport(int x, int y, int width, int height) { call(glViewport, x, y, width, height); }
    void drawArrays(int mode, int first, int count) { call(glDrawArrays, mode, first, count); }
    void pixelStorei(int name, int value) { call(glPixelStorei, name, value); }
    void readPixels(int x, int y, int width, int height, int format, int type, MemorySegment target) {
        call(glReadPixels, x, y, width, height, format, type, target);
    }

    // --- shaders ----------------------------------------------------------------

    /** Compiles and links a program; a compile or link error carries the driver's log. */
    int program(String vertexSource, String fragmentSource) {
        int vertex = shader(GL_VERTEX_SHADER, vertexSource);
        int fragment = shader(GL_FRAGMENT_SHADER, fragmentSource);
        int program = (int) call(glCreateProgram);
        call(glAttachShader, program, vertex);
        call(glAttachShader, program, fragment);
        call(glLinkProgram, program);
        call(glDeleteShader, vertex);
        call(glDeleteShader, fragment);
        if (status(glGetProgramiv, program, GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("Shader link failed: " + infoLog(glGetProgramInfoLog, program));
        }
        return program;
    }

    private int shader(int type, String source) {
        int shader = (int) call(glCreateShader, type);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment strings = arena.allocateFrom(ADDRESS, arena.allocateFrom(source));
            call(glShaderSource, shader, 1, strings, MemorySegment.NULL);
        }
        call(glCompileShader, shader);
        if (status(glGetShaderiv, shader, GL_COMPILE_STATUS) == 0) {
            throw new IllegalStateException("Shader compile failed: " + infoLog(glGetShaderInfoLog, shader));
        }
        return shader;
    }

    private int status(MethodHandle getter, int name, int parameter) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_INT);
            call(getter, name, parameter, value);
            return value.get(JAVA_INT, 0);
        }
    }

    private String infoLog(MethodHandle getter, int name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment log = arena.allocate(8192);
            call(getter, name, (int) log.byteSize(), MemorySegment.NULL, log);
            return log.getString(0);
        }
    }

    void useProgram(int program) { call(glUseProgram, program); }
    void deleteProgram(int program) { call(glDeleteProgram, program); }

    int uniformLocation(int program, String name) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) call(glGetUniformLocation, program, arena.allocateFrom(name));
        }
    }

    void uniform1i(int location, int value) { call(glUniform1i, location, value); }
    void uniform1f(int location, float value) { call(glUniform1f, location, value); }
    void uniform2f(int location, float x, float y) { call(glUniform2f, location, x, y); }
    void uniform3f(int location, float x, float y, float z) { call(glUniform3f, location, x, y, z); }
}
