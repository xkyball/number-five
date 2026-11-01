#pragma once
#include "no5_platform.hpp"
#include <iostream>
// The original GLES shader code uses qDebug only for diagnostics. No Qt runtime.
inline std::ostream& qDebug(){return std::cerr;}
