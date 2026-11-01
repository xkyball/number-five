#include "s52_context.hpp"
#include "no5_area_gpu.hpp"
#include "Cs52_shaders.h"
#include "s52shaders.h"
#include <array>
#include <stdexcept>
#include <unordered_map>

extern thread_local GLint S52color_tri_shader_program;
extern thread_local GLint S52texture_2D_shader_program;
extern thread_local GLint S52texture_2D_ColorMod_shader_program;
extern thread_local GLint S52circle_filled_shader_program;
extern thread_local GLint S52ring_shader_program;
extern thread_local GLint S52Dash_shader_program;
extern thread_local GLint S52AP_shader_program;
extern thread_local bool shadersLoaded;
extern thread_local bool bCShadersLoaded[2];
extern thread_local CGLShaderProgram* pCAALine_shader_program[2];
extern thread_local CGLShaderProgram* pCcircle_filled_shader_program[2];
extern thread_local CGLShaderProgram* pCtexture_2DA_shader_program[2];
extern thread_local CGLShaderProgram* m_TexFontShader;

namespace no5 {
namespace {
struct ShaderState {
  std::array<GLint, 7> programs{};
  bool loaded = false;
  std::array<bool, 2> cLoaded{};
  std::array<std::array<CGLShaderProgram*, 2>, 6> cPrograms{};
  CGLShaderProgram* font = nullptr;
};
thread_local EGLContext activeContext = EGL_NO_CONTEXT;
thread_local std::unordered_map<EGLContext, ShaderState> states;

auto programGlobals() {
  return std::array<GLint*, 7>{&S52color_tri_shader_program,
    &S52texture_2D_shader_program, &S52texture_2D_ColorMod_shader_program,
    &S52circle_filled_shader_program, &S52ring_shader_program,
    &S52Dash_shader_program, &S52AP_shader_program};
}
auto cProgramGlobals() {
  return std::array<CGLShaderProgram**, 6>{pCAALine_shader_program,
    pCcolor_tri_shader_program, pCtexture_2D_shader_program,
    pCcircle_filled_shader_program, pCtexture_2DA_shader_program,
    pCtexture_2D_Color_shader_program};
}
ShaderState capture() {
  ShaderState result;
  const auto programs = programGlobals();
  for (size_t i = 0; i < programs.size(); ++i) result.programs[i] = *programs[i];
  result.loaded = shadersLoaded;
  const auto cPrograms = cProgramGlobals();
  for (size_t i = 0; i < 2; ++i) result.cLoaded[i] = bCShadersLoaded[i];
  for (size_t i = 0; i < cPrograms.size(); ++i)
    for (size_t j = 0; j < 2; ++j) result.cPrograms[i][j] = cPrograms[i][j];
  result.font = m_TexFontShader;
  return result;
}
void restore(const ShaderState& state) {
  const auto programs = programGlobals();
  for (size_t i = 0; i < programs.size(); ++i) *programs[i] = state.programs[i];
  shadersLoaded = state.loaded;
  const auto cPrograms = cProgramGlobals();
  for (size_t i = 0; i < 2; ++i) bCShadersLoaded[i] = state.cLoaded[i];
  for (size_t i = 0; i < cPrograms.size(); ++i)
    for (size_t j = 0; j < 2; ++j) cPrograms[i][j] = state.cPrograms[i][j];
  m_TexFontShader = state.font;
}
void abandon(ShaderState& state) {
  // EGL destruction owns the lost GL objects; only the C++ wrappers remain.
  for (auto& programs : state.cPrograms) for (auto*& program : programs) {
    if (program) { program->abandonContext(); delete program; program = nullptr; }
  }
  if (state.font) { state.font->abandonContext(); delete state.font; state.font = nullptr; }
}
}

void activateS52Context(EGLContext context) {
  if (context == EGL_NO_CONTEXT || context != eglGetCurrentContext())
    throw std::runtime_error("S52 requires the current owned EGL context");
  if (context == activeContext) return;
  if (activeContext != EGL_NO_CONTEXT) states.at(activeContext) = capture();
  auto [found, inserted] = states.try_emplace(context);
  restore(found->second); activeContext = context;
}
void releaseS52Context(EGLContext context) {
  activateS52Context(context);
  releaseAreaGpuContext(context);
  unloadS52Shaders(); unloadCShaders();
  // Include retained, currently unused shader slots in the ownership boundary.
  for (auto* programs : cProgramGlobals()) for (size_t i = 0; i < 2; ++i) {
    delete programs[i]; programs[i] = nullptr;
  }
  delete m_TexFontShader; m_TexFontShader = nullptr;
  states.at(context) = capture();
}
void forgetS52Context(EGLContext context) {
  forgetAreaGpuContext(context);
  auto found = states.find(context);
  if (found == states.end()) return;
  if (activeContext == context) found->second = capture();
  abandon(found->second);
  if (activeContext == context) { restore(ShaderState{}); activeContext = EGL_NO_CONTEXT; }
  states.erase(found);
}
}
