#include "s52plib.h"
#include "vector2D.h"
double vGetLengthOfNormal(pVector2D a, pVector2D b, pVector2D n) {
  vector2D c, vNormal;
  vNormal.x = 0;
  vNormal.y = 0;
  //
  // Obtain projection vector.
  //
  // c = ((a * b)/(|b|^2))*b
  //
  c.x = b->x * (vDotProduct(a, b) / vDotProduct(b, b));
  c.y = b->y * (vDotProduct(a, b) / vDotProduct(b, b));
  //
  // Obtain perpendicular projection : e = a - c
  //
  vSubtractVectors(a, &c, &vNormal);
  //
  // Fill PROJECTION structure with appropriate values.
  //
  *n = vNormal;

  return (vVectorMagnitude(&vNormal));
}
double vDotProduct(pVector2D v0, pVector2D v1) {
  double dotprod;

  dotprod =
      (v0 == NULL || v1 == NULL) ? 0.0 : (v0->x * v1->x) + (v0->y * v1->y);

  return (dotprod);
}
double vVectorSquared(pVector2D v0) {
  double dS;

  if (v0 == NULL)
    dS = 0.0;
  else
    dS = ((v0->x * v0->x) + (v0->y * v0->y));
  return (dS);
}
pVector2D vAddVectors(pVector2D v0, pVector2D v1, pVector2D v) {
  if (v0 == NULL || v1 == NULL)
    v = (pVector2D)NULL;
  else {
    v->x = v0->x + v1->x;
    v->y = v0->y + v1->y;
  }
  return (v);
}
pVector2D vSubtractVectors(pVector2D v0, pVector2D v1, pVector2D v) {
  if (v0 == NULL || v1 == NULL)
    v = (pVector2D)NULL;
  else {
    v->x = v0->x - v1->x;
    v->y = v0->y - v1->y;
  }
  return (v);
}
render_canvas_parms::render_canvas_parms() { pix_buff = NULL; }
render_canvas_parms::~render_canvas_parms() {}

double vVectorMagnitude(pVector2D v0) {
  double dMagnitude;

  if (v0 == NULL)
    dMagnitude = 0.0;
  else
    dMagnitude = sqrt(vVectorSquared(v0));
  return (dMagnitude);
}
