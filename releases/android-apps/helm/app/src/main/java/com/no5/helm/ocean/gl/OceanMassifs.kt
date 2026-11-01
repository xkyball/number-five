package com.no5.helm.ocean.gl

import kotlin.math.*

/** Authored decorative rock groups. Distances are scene metres, never chart geography. */
internal object OceanMassifs {
    private data class Massif(val bearing: Float,val distance: Float,val width: Float,val depth: Float,val height: Float,val kind: Int)
    private val groups=listOf(
        Massif(-28f,6500f,1300f,850f,520f,0), Massif(20f,6500f,1200f,900f,620f,1),
        Massif(60f,4500f,900f,650f,300f,2), Massif(100f,6500f,1350f,850f,630f,1),
        Massif(175f,4600f,1100f,600f,310f,2), Massif(212f,6900f,1500f,850f,580f,0),
        Massif(275f,7400f,1100f,700f,650f,1))
    private val profiles=arrayOf(
        floatArrayOf(-1.1f,-.035f,-.92f,.08f,-.64f,.25f,-.49f,.30f,-.51f,.62f,-.55f,.86f,-.52f,.97f,-.36f,1f,0f,1f,.30f,.95f,.55f,.64f,.84f,.18f,1f,-.035f),
        floatArrayOf(-1f,-.035f,-.8f,.1f,-.56f,.25f,-.39f,.32f,-.31f,.68f,-.18f,.96f,-.1f,1f,.06f,.62f,.24f,.52f,.38f,.32f,.78f,.11f,1f,-.035f),
        floatArrayOf(-1f,-.035f,-.95f,.04f,-.78f,.13f,-.75f,.43f,-.73f,.46f,-.54f,.46f,-.53f,.77f,-.50f,.80f,-.19f,.84f,.02f,1f,.31f,.78f,.6f,.24f,1f,-.035f))
    private val crowns=arrayOf(
        floatArrayOf(-1f,0f,-.88f,.35f,-.72f,.93f,-.56f,.96f,-.41f,1f,-.24f,.96f,-.06f,1f,.16f,.98f,.34f,.94f,.52f,.96f,.72f,.83f,.86f,.44f,1f,0f),
        floatArrayOf(-1f,0f,-.9f,.25f,-.72f,.62f,-.52f,1.03f,-.31f,.82f,-.12f,.69f,.05f,.95f,.27f,1.08f,.42f,.74f,.57f,.92f,.81f,.5f,1f,0f))
    private fun crown(u: Float,kind: Int): Float {
        val a=crowns[if(kind==1) 1 else 0]
        for(i in 0 until a.size-2 step 2) if(u<=a[i+2]) {
            val t=((u-a[i])/(a[i+2]-a[i])).coerceIn(0f,1f)
            return a[i+1]*(1-t)+a[i+3]*t
        }
        return 0f
    }
    private fun gully(u: Float,seed: Int): Float = listOf(-.58f,-.17f,.28f,.56f).maxOf {
        (1-abs(u-it-(seed%3-1)*.025f)/.042f).coerceIn(0f,1f)
    }
    fun mesh(low: Boolean): OceanMesh {
        val vertices=ArrayList<Float>(); val indices=ArrayList<Int>()
        val columns=if(low) 64 else 128; val rows=if(low) 2 else 3
        groups.forEachIndexed { seed,m ->
            val angle=m.bearing*PI.toFloat()/180; val sx=sin(angle); val cz=cos(angle)
            val profile=profiles[m.kind]
            fun point(u: Float,panel: Int,t: Float): FloatArray {
                val d=profile[panel*2]*(1-t)+profile[panel*2+2]*t
                val h=profile[panel*2+1]*(1-t)+profile[panel*2+3]*t
                val edge=crown(u,m.kind)
                val strike=u+h*(if(m.kind==1) .16f else .025f)
                val cut=gully(strike,seed)
                val front=(1-d).coerceIn(0f,1f)
                val rib=(1-abs(((strike+1)*13+seed*.27f)%2-1))*65f
                val fracture=sin(strike*63+seed)*sin(h*21+strike*13)*18f+
                    sin(strike*117+h*32)*7f
                val radial=m.distance+d*m.depth+(cut*210-rib+fracture)*front*edge
                val y=-24+m.height*edge*h-cut*62*edge*h.coerceAtLeast(0f)
                return floatArrayOf(sx*radial+cz*u*m.width,y,cz*radial-sx*u*m.width,d)
            }
            // Panels share positions but retain separate normals at bedding/overhang edges.
            for(panel in 0 until profile.size/2-1) {
                val start=vertices.size/11
                for(row in 0..rows) for(column in 0..columns) {
                    val u=column.toFloat()/columns*2-1; val t=row.toFloat()/rows
                    val p=point(u,panel,t)
                    val left=point((u-.001f).coerceAtLeast(-1f),panel,t)
                    val right=point((u+.001f).coerceAtMost(1f),panel,t)
                    val back=point(u,panel,1f); val front=point(u,panel,0f)
                    val a=FloatArray(3) { right[it]-left[it] }; val b=FloatArray(3) { back[it]-front[it] }
                    val n=floatArrayOf(b[1]*a[2]-b[2]*a[1],b[2]*a[0]-b[0]*a[2],b[0]*a[1]-b[1]*a[0])
                    val length=sqrt(n.sumOf { (it*it).toDouble() }).toFloat().coerceAtLeast(.00001f)
                    vertices.addAll(listOf(p[0],p[1],p[2],n[0]/length,n[1]/length,n[2]/length,
                        1-gully(u,seed)*.30f,u*m.width,p[3]*m.depth,m.kind.toFloat(),seed.toFloat()))
                }
                for(row in 0 until rows) for(column in 0 until columns) {
                    val a=start+row*(columns+1)+column; val b=a+columns+1
                    indices.addAll(listOf(a,b,a+1,a+1,b,b+1))
                }
            }
        }
        return OceanMesh(vertices.toFloatArray(),indices.toIntArray(),11)
    }
}
