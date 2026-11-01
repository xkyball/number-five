#pragma once
#include <array>
#include <cstddef>

namespace no5 {
// One rule owns one batch: colour, line width, clip, priority and segment order
// cannot cross this boundary. Both endpoints carry the original segment start
// in framebuffer coordinates, so dash phase resets exactly as before.
struct DashVertex { float x, y, startX, startY; };
template<class Submit, std::size_t VertexLimit = 2048> class DashBatch {
 static_assert(VertexLimit >= 2 && VertexLimit % 2 == 0, "whole GL_LINES pairs");
 std::array<DashVertex, VertexLimit> vertices{};
 std::size_t used = 0;
 Submit& submit;
public:
 explicit DashBatch(Submit& call):submit(call) {}
 void append(float x0, float y0, float x1, float y1, float startX, float startY) {
  if (used == VertexLimit) flush();
  vertices[used++] = {x0,y0,startX,startY};
  vertices[used++] = {x1,y1,startX,startY};
 }
 void flush() { if (used) { submit(vertices.data(), used); used = 0; } }
};
}
