#include <GLES2/gl2.h>
#include <algorithm>
#include <jni.h>
#include <string>

namespace {
struct EngineState {
    int surface_width = 1;
    int surface_height = 1;
    float touch_x = 0.5f;
    float touch_y = 0.5f;
    bool touch_down = false;
    GLuint program = 0;
    GLuint vbo = 0;
    GLint attr_pos = -1;
    GLint uni_offset = -1;
    GLint uni_color = -1;
    std::string swipe_buffer;
    char last_swipe_key = '\0';
    bool swipe_active = false;
};

EngineState g_engine;

int Utf16Len(JNIEnv* env, jstring value) {
    return value == nullptr ? 0 : env->GetStringLength(value);
}

void CallSolidify(JNIEnv* env, const char* text) {
    jclass klass = env->FindClass("com/jormy/nin/SoftKeyboard");
    if (klass == nullptr) {
        env->ExceptionClear();
        return;
    }
    jmethodID method = env->GetStaticMethodID(klass, "callSolidify", "(Ljava/lang/String;)V");
    if (method == nullptr) {
        env->DeleteLocalRef(klass);
        env->ExceptionClear();
        return;
    }
    jstring value = env->NewStringUTF(text);
    env->CallStaticVoidMethod(klass, method, value);
    env->DeleteLocalRef(value);
    env->DeleteLocalRef(klass);
}

void CallMarkLiquid(JNIEnv* env, const char* text) {
    jclass klass = env->FindClass("com/jormy/nin/SoftKeyboard");
    if (klass == nullptr) {
        env->ExceptionClear();
        return;
    }
    jmethodID method = env->GetStaticMethodID(klass, "callMarkLiquid", "(Ljava/lang/String;)V");
    if (method == nullptr) {
        env->DeleteLocalRef(klass);
        env->ExceptionClear();
        return;
    }
    jstring value = env->NewStringUTF(text);
    env->CallStaticVoidMethod(klass, method, value);
    env->DeleteLocalRef(value);
    env->DeleteLocalRef(klass);
}

void CallSimpleBackspace(JNIEnv* env, bool single_char_mode) {
    jclass klass = env->FindClass("com/jormy/nin/SoftKeyboard");
    if (klass == nullptr) {
        env->ExceptionClear();
        return;
    }
    jmethodID method = env->GetStaticMethodID(klass, "callSimpleBackspace", "(Z)V");
    if (method == nullptr) {
        env->DeleteLocalRef(klass);
        env->ExceptionClear();
        return;
    }
    env->CallStaticVoidMethod(klass, method, static_cast<jboolean>(single_char_mode));
    env->DeleteLocalRef(klass);
}

char ResolveSwipeKey(float nx, float ny) {
    if (ny > 0.80f) {
        return '\0';
    }

    const char* row = nullptr;
    int count = 0;
    if (ny < 0.33f) {
        row = "qwertyuiop";
        count = 10;
    } else if (ny < 0.66f) {
        row = "asdfghjkl";
        count = 9;
    } else {
        row = "zxcvbnm,.";
        count = 9;
    }

    int index = static_cast<int>(nx * static_cast<float>(count));
    if (index < 0) {
        index = 0;
    } else if (index >= count) {
        index = count - 1;
    }
    return row[index];
}

void EmitTouchTap(JNIEnv* env, float nx, float ny) {
    if (ny > 0.80f) {
        if (nx < 0.20f) {
            CallSimpleBackspace(env, true);
            return;
        }
        if (nx > 0.80f) {
            CallSolidify(env, "\n");
            return;
        }
        CallSolidify(env, " ");
        return;
    }

    char out[2] = {ResolveSwipeKey(nx, ny), '\0'};
    if (out[0] == '\0') {
        return;
    }
    CallSolidify(env, out);
}

GLuint CompileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    if (shader == 0) {
        return 0;
    }
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (ok == GL_TRUE) {
        return shader;
    }
    glDeleteShader(shader);
    return 0;
}

void EnsureProgram() {
    if (g_engine.program != 0) {
        return;
    }

    static const char* kVertexShader = R"(
        attribute vec2 aPos;
        uniform vec2 uOffset;
        void main() {
            gl_Position = vec4(aPos + uOffset, 0.0, 1.0);
        }
    )";

    static const char* kFragmentShader = R"(
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            gl_FragColor = uColor;
        }
    )";

    GLuint vertex = CompileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fragment = CompileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    if (vertex == 0 || fragment == 0) {
        if (vertex != 0) glDeleteShader(vertex);
        if (fragment != 0) glDeleteShader(fragment);
        return;
    }

    GLuint program = glCreateProgram();
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);

    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        glDeleteProgram(program);
        return;
    }

    const GLfloat triangle_vertices[] = {
        -0.018f, -0.022f,
         0.018f, -0.022f,
         0.000f,  0.028f
    };
    GLuint vbo = 0;
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(triangle_vertices), triangle_vertices, GL_STATIC_DRAW);

    g_engine.program = program;
    g_engine.vbo = vbo;
    g_engine.attr_pos = glGetAttribLocation(program, "aPos");
    g_engine.uni_offset = glGetUniformLocation(program, "uOffset");
    g_engine.uni_color = glGetUniformLocation(program, "uColor");
}

void RenderFrame() {
    EnsureProgram();

    glViewport(0, 0, g_engine.surface_width, g_engine.surface_height);
    if (g_engine.touch_down) {
        glClearColor(0.08f, 0.14f, 0.20f, 1.0f);
    } else {
        glClearColor(0.02f, 0.02f, 0.03f, 1.0f);
    }
    glClear(GL_COLOR_BUFFER_BIT);

    if (g_engine.program == 0 || g_engine.vbo == 0 || !g_engine.touch_down) {
        return;
    }

    const float offset_x = g_engine.touch_x * 2.0f - 1.0f;
    const float offset_y = 1.0f - g_engine.touch_y * 2.0f;

    glUseProgram(g_engine.program);
    glBindBuffer(GL_ARRAY_BUFFER, g_engine.vbo);
    glEnableVertexAttribArray(static_cast<GLuint>(g_engine.attr_pos));
    glVertexAttribPointer(
        static_cast<GLuint>(g_engine.attr_pos),
        2,
        GL_FLOAT,
        GL_FALSE,
        2 * sizeof(GLfloat),
        reinterpret_cast<void*>(0)
    );
    glUniform2f(g_engine.uni_offset, offset_x, offset_y);
    glUniform4f(g_engine.uni_color, 0.95f, 0.86f, 0.25f, 1.0f);
    glDrawArrays(GL_TRIANGLES, 0, 3);
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jormy_nin_NINLib_getUnicodeBackIndex(JNIEnv* env, jclass, jstring str, jint i) {
    const int len = Utf16Len(env, str);
    const int clamped = std::clamp(i, 0, len);
    return std::max(0, clamped - 1);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jormy_nin_NINLib_getUnicodeFrontIndex(JNIEnv* env, jclass, jstring str, jint i) {
    const int len = Utf16Len(env, str);
    const int clamped = std::clamp(i, 0, len);
    return std::min(len, clamped + 1);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jormy_nin_NINLib_init(JNIEnv*, jclass, jint surface_width, jint surface_height, jint, jint) {
    g_engine.surface_width = std::max(1, surface_width);
    g_engine.surface_height = std::max(1, surface_height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jormy_nin_NINLib_memTestStep(JNIEnv*, jclass) {}

extern "C" JNIEXPORT void JNICALL
Java_com_jormy_nin_NINLib_onChangeAppOrTextbox(JNIEnv*, jclass, jstring, jstring, jstring) {}

extern "C" JNIEXPORT void JNICALL
Java_com_jormy_nin_NINLib_onEditorChangeTypeClass(JNIEnv*, jclass, jstring, jstring) {}

extern "C" JNIEXPORT void JNICALL
Java_com_jormy_nin_NINLib_onExternalSelChange(JNIEnv*, jclass) {}

extern "C" JNIEXPORT void JNICALL
Java_com_jormy_nin_NINLib_onTextSelection(JNIEnv*, jclass, jstring, jstring, jstring, jstring) {}

extern "C" JNIEXPORT void JNICALL
Java_com_jormy_nin_NINLib_onTouchEvent(JNIEnv* env, jclass, jint, jint action, jfloat x, jfloat y, jfloat, jfloat, jlong) {
    const float width = static_cast<float>(std::max(g_engine.surface_width, 1));
    const float height = static_cast<float>(std::max(g_engine.surface_height, 1));
    g_engine.touch_x = std::clamp(x / width, 0.0f, 1.0f);
    g_engine.touch_y = std::clamp(y / height, 0.0f, 1.0f);
    g_engine.touch_down = action != 2;
    if (action == 0) {
        g_engine.swipe_active = true;
        g_engine.swipe_buffer.clear();
        g_engine.last_swipe_key = '\0';

        char key = ResolveSwipeKey(g_engine.touch_x, g_engine.touch_y);
        if (key != '\0') {
            g_engine.last_swipe_key = key;
            g_engine.swipe_buffer.push_back(key);
            CallMarkLiquid(env, g_engine.swipe_buffer.c_str());
        }
        return;
    }

    if (action == 1 && g_engine.swipe_active) {
        char key = ResolveSwipeKey(g_engine.touch_x, g_engine.touch_y);
        if (key != '\0' && key != g_engine.last_swipe_key) {
            g_engine.last_swipe_key = key;
            g_engine.swipe_buffer.push_back(key);
            CallMarkLiquid(env, g_engine.swipe_buffer.c_str());
        }
        return;
    }

    if (action == 2) {
        if (g_engine.swipe_active && !g_engine.swipe_buffer.empty()) {
            CallSolidify(env, g_engine.swipe_buffer.c_str());
        } else {
            EmitTouchTap(env, g_engine.touch_x, g_engine.touch_y);
        }
        g_engine.swipe_active = false;
        g_engine.swipe_buffer.clear();
        g_engine.last_swipe_key = '\0';
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_jormy_nin_NINLib_onWordDestruction(JNIEnv*, jclass, jstring, jstring) {}

extern "C" JNIEXPORT jint JNICALL
Java_com_jormy_nin_NINLib_processBackspaceAllowance(JNIEnv* env, jclass, jstring str, jstring, jint i) {
    const int len = Utf16Len(env, str);
    return std::clamp(i, 0, len);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jormy_nin_NINLib_processSoftKeyboardCursorMovementLeft(JNIEnv*, jclass, jstring) {
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jormy_nin_NINLib_processSoftKeyboardCursorMovementRight(JNIEnv*, jclass, jstring) {
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jormy_nin_NINLib_step(JNIEnv*, jclass) {
    RenderFrame();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jormy_nin_NINLib_syncTiming(JNIEnv*, jclass, jlong now) {
    return now;
}
