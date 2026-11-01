#pragma once
#include <EGL/egl.h>

namespace no5 {
// Retained S52 entry points use globals. Bind their thread-local view to the
// current owned EGL context, preserving other contexts' resources.
void activateS52Context(EGLContext context);
void releaseS52Context(EGLContext context);
// Drop CPU wrappers without GL calls when a lost context cannot be activated.
void forgetS52Context(EGLContext context);
}
