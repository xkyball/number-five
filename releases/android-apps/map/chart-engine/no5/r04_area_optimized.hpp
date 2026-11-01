// GPL-2.0-or-later. Equivalent runtime variant; the selected R04 source stays intact.
#pragma once
#include "r04_area_shader.hpp"
#include <string>
#include <stdexcept>
namespace no5 {
inline std::string r04AreaSimplifiedFragment(){
 std::string source(r04AreaFragment);
 auto replace=[&](const std::string& before,const std::string& after){auto at=source.find(before);if(at==std::string::npos||source.find(before,at+before.size())!=std::string::npos)throw std::logic_error("R04 simplification source contract changed");source.replace(at,before.size(),after);};
 // Reuse exactly the classification already needed for the first edge. Keep
 // the same sample coordinate, float operations, radius count and weights.
 replace("float a=edge(vec2(-r,r),c),b=edge(vec2(r,-r),c);",
         "float n=category(texture2D(areas,clamp(uv+vec2(-r,r)*pixel,vec2(0.),vec2(1.))).rgb);\n      float a=n>=0.&&surface(n)!=surface(c)?1.:0.,b=edge(vec2(r,-r),c);");
 replace("      float n=category(texture2D(areas,clamp(uv+vec2(-r,r)*pixel,vec2(0.),vec2(1.))).rgb);\n      if(c<3.5", "      if(c<3.5");
 return source;
}
}
