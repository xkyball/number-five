#include "chart_model.hpp"
#include "poly_math.h"
#include "vector2D.h"

std::list<S57Obj *> *s57chart::GetAssociatedObjects(S57Obj *obj) {
  int disPrioIdx;
  bool gotit;

  std::list<S57Obj *> *pobj_list = new std::list<S57Obj *>();

  double lat, lon;
  fromSM_plib((obj->x * obj->x_rate) + obj->x_origin,
         (obj->y * obj->y_rate) + obj->y_origin, ref_lat, ref_lon, &lat, &lon);
  //    What is the entry object geometry type?

  switch (obj->Primitive_type) {
    case GEO_POINT:
      //  n.b.  This logic not perfectly right for LINE and AREA features
      //  It uses the object reference point for testing, instead of the
      //  decomposed line or boundary geometry.  Thus, it may fail on some
      //  intersecting relationships. Judged acceptable, in favor of performance
      //  implications. DSR
    case GEO_LINE:
    case GEO_AREA:
      ObjRazRules *top;
      disPrioIdx = 1;  // PRIO_GROUP1:S57 group 1 filled areas

      gotit = false;
      top = razRules[disPrioIdx][3];  // PLAIN_BOUNDARIES
      while (top != NULL) {
        if (top->obj->bIsAssociable) {
          if (top->obj->BBObj.Contains(lat, lon)) {
            if (IsPointInObjArea(lat, lon, 0.0, top->obj)) {
              pobj_list->push_back(top->obj);
              gotit = true;
              break;
            }
          }
        }

        ObjRazRules *nxx = top->next;
        top = nxx;
      }

      if (!gotit) {
        top = razRules[disPrioIdx][4];  // SYMBOLIZED_BOUNDARIES
        while (top != NULL) {
          if (top->obj->bIsAssociable) {
            if (top->obj->BBObj.Contains(lat, lon)) {
              if (IsPointInObjArea(lat, lon, 0.0, top->obj)) {
                pobj_list->push_back(top->obj);
                break;
              }
            }
          }

          ObjRazRules *nxx = top->next;
          top = nxx;
        }
      }

      break;

    default:
      break;
  }

  return pobj_list;
}

bool s57chart::IsPointInObjArea(float lat, float lon, float select_radius,
                                S57Obj *obj) {
  bool ret = false;

  if (obj->pPolyTessGeo) {
    if (!obj->pPolyTessGeo->IsOk()) obj->pPolyTessGeo->BuildDeferredTess();

    PolyTriGroup *ppg = obj->pPolyTessGeo->Get_PolyTriGroup_head();

    TriPrim *pTP = ppg->tri_prim_head;

    MyPoint pvert_list[3];

    //  Polygon geometry is carried in SM coordinates, so...
    //  make the hit test thus.
    double easting, northing;
    toSM_plib(lat, lon, ref_lat, ref_lon, &easting, &northing);

    //  On some chart types (e.g. cm93), the tesseleated coordinates are stored
    //  differently. Adjust the pick point (easting/northing) to correspond.
    if (!ppg->m_bSMSENC) {
      double y_rate = obj->y_rate;
      double y_origin = obj->y_origin;
      double x_rate = obj->x_rate;
      double x_origin = obj->x_origin;

      double northing_scaled = (northing - y_origin) / y_rate;
      double easting_scaled = (easting - x_origin) / x_rate;
      northing = northing_scaled;
      easting = easting_scaled;
    }

    while (pTP) {
      //  Coarse test
      if (pTP->tri_box.Contains(lat, lon)) {
        if (ppg->data_type == DATA_TYPE_DOUBLE) {
          double *p_vertex = pTP->p_vertex;

          switch (pTP->type) {
            case PTG_TRIANGLE_FAN: {
              for (int it = 0; it < pTP->nVert - 2; it++) {
                pvert_list[0].x = p_vertex[0];
                pvert_list[0].y = p_vertex[1];

                pvert_list[1].x = p_vertex[(it * 2) + 2];
                pvert_list[1].y = p_vertex[(it * 2) + 3];

                pvert_list[2].x = p_vertex[(it * 2) + 4];
                pvert_list[2].y = p_vertex[(it * 2) + 5];

                if (G_PtInPolygon((MyPoint *)pvert_list, 3, easting,
                                  northing)) {
                  ret = true;
                  break;
                }
              }
              break;
            }
            case PTG_TRIANGLE_STRIP: {
              for (int it = 0; it < pTP->nVert - 2; it++) {
                pvert_list[0].x = p_vertex[(it * 2)];
                pvert_list[0].y = p_vertex[(it * 2) + 1];

                pvert_list[1].x = p_vertex[(it * 2) + 2];
                pvert_list[1].y = p_vertex[(it * 2) + 3];

                pvert_list[2].x = p_vertex[(it * 2) + 4];
                pvert_list[2].y = p_vertex[(it * 2) + 5];

                if (G_PtInPolygon((MyPoint *)pvert_list, 3, easting,
                                  northing)) {
                  ret = true;
                  break;
                }
              }
              break;
            }
            case PTG_TRIANGLES: {
              for (int it = 0; it < pTP->nVert; it += 3) {
                pvert_list[0].x = p_vertex[(it * 2)];
                pvert_list[0].y = p_vertex[(it * 2) + 1];

                pvert_list[1].x = p_vertex[(it * 2) + 2];
                pvert_list[1].y = p_vertex[(it * 2) + 3];

                pvert_list[2].x = p_vertex[(it * 2) + 4];
                pvert_list[2].y = p_vertex[(it * 2) + 5];

                if (G_PtInPolygon((MyPoint *)pvert_list, 3, easting,
                                  northing)) {
                  ret = true;
                  break;
                }
              }
              break;
            }
          }
        } else if (ppg->data_type == DATA_TYPE_FLOAT) {
          float *p_vertex = (float *)pTP->p_vertex;

          switch (pTP->type) {
            case PTG_TRIANGLE_FAN: {
              for (int it = 0; it < pTP->nVert - 2; it++) {
                pvert_list[0].x = p_vertex[0];
                pvert_list[0].y = p_vertex[1];

                pvert_list[1].x = p_vertex[(it * 2) + 2];
                pvert_list[1].y = p_vertex[(it * 2) + 3];

                pvert_list[2].x = p_vertex[(it * 2) + 4];
                pvert_list[2].y = p_vertex[(it * 2) + 5];

                if (G_PtInPolygon((MyPoint *)pvert_list, 3, easting,
                                  northing)) {
                  ret = true;
                  break;
                }
              }
              break;
            }
            case PTG_TRIANGLE_STRIP: {
              for (int it = 0; it < pTP->nVert - 2; it++) {
                pvert_list[0].x = p_vertex[(it * 2)];
                pvert_list[0].y = p_vertex[(it * 2) + 1];

                pvert_list[1].x = p_vertex[(it * 2) + 2];
                pvert_list[1].y = p_vertex[(it * 2) + 3];

                pvert_list[2].x = p_vertex[(it * 2) + 4];
                pvert_list[2].y = p_vertex[(it * 2) + 5];

                if (G_PtInPolygon((MyPoint *)pvert_list, 3, easting,
                                  northing)) {
                  ret = true;
                  break;
                }
              }
              break;
            }
            case PTG_TRIANGLES: {
              for (int it = 0; it < pTP->nVert; it += 3) {
                pvert_list[0].x = p_vertex[(it * 2)];
                pvert_list[0].y = p_vertex[(it * 2) + 1];

                pvert_list[1].x = p_vertex[(it * 2) + 2];
                pvert_list[1].y = p_vertex[(it * 2) + 3];

                pvert_list[2].x = p_vertex[(it * 2) + 4];
                pvert_list[2].y = p_vertex[(it * 2) + 5];

                if (G_PtInPolygon((MyPoint *)pvert_list, 3, easting,
                                  northing)) {
                  ret = true;
                  break;
                }
              }
              break;
            }
          }
        } else {
          ret = true;  // Unknown data type, accept the entire TriPrim via
                       // coarse test.
          break;
        }
      }
      pTP = pTP->p_next;
    }

  }  // if pPolyTessGeo

  return ret;
}

bool s57chart::DoesLatLonSelectObject(float lat, float lon, float select_radius,
                                      S57Obj *obj) {
  switch (obj->Primitive_type) {
    //  For single Point objects, the integral object bounding box contains the
    //  lat/lon of the object, possibly expanded by text or symbol rendering
    case GEO_POINT: {
      if (!obj->BBObj.GetValid()) return false;

      if (1 == obj->npt) {
        //  Special case for LIGHTS
        //  Sector lights have had their BBObj expanded to include the entire
        //  drawn sector This is too big for pick area, can be confusing.... So
        //  make a temporary box at the light's lat/lon, with select_radius size
        if (!strncmp(obj->FeatureName, "LIGHTS", 6)) {
          double sectrTest;
          bool hasSectors = GetDoubleAttr(obj, "SECTR1", sectrTest);
          if (hasSectors) {
            double olon, olat;
            fromSM_plib((obj->x * obj->x_rate) + obj->x_origin,
                   (obj->y * obj->y_rate) + obj->y_origin, ref_lat, ref_lon,
                   &olat, &olon);

            // Double the select radius to adjust for the fact that LIGHTS has
            // a 0x0 BBox to start with, which makes it smaller than all other
            // rendered objects.
            LLBBox sbox;
            sbox.Set(olat, olon, olat, olon);

            if (sbox.ContainsMarge(lat, lon, select_radius)) return true;
          } else if (obj->BBObj.ContainsMarge(lat, lon, select_radius))
            return true;

        }

        else if (obj->BBObj.ContainsMarge(lat, lon, select_radius))
          return true;
      }

      //  For MultiPoint objects, make a bounding box from each point's lat/lon
      //  and check it
      else {
        if (!obj->BBObj.GetValid()) return false;

        //  Coarse test first
        if (!obj->BBObj.ContainsMarge(lat, lon, select_radius)) return false;
        //  Now decomposed soundings, one by one
        double *pdl = obj->geoPtMulti;
        for (int ip = 0; ip < obj->npt; ip++) {
          double lon_point = *pdl++;
          double lat_point = *pdl++;
          LLBBox BB_point;
          BB_point.Set(lat_point, lon_point, lat_point, lon_point);
          if (BB_point.ContainsMarge(lat, lon, select_radius)) {
            //                                  index = ip;
            return true;
          }
        }
      }

      break;
    }
    case GEO_AREA: {
      //  Coarse test first
      if (!obj->BBObj.ContainsMarge(lat, lon, select_radius))
        return false;
      else
        return IsPointInObjArea(lat, lon, select_radius, obj);
    }

    case GEO_LINE: {
      //  Coarse test first
      if (!obj->BBObj.ContainsMarge(lat, lon, select_radius)) return false;

      float sel_rad_meters = select_radius * 1852 * 60;  // approximately
      double easting, northing;
      toSM_plib(lat, lon, ref_lat, ref_lon, &easting, &northing);

      if (obj->geoPt) {
        //  Line geometry is carried in SM or CM93 coordinates, so...
        //  make the hit test using SM coordinates, converting from object
        //  points to SM using per-object conversion factors.

        pt *ppt = obj->geoPt;
        int npt = obj->npt;

        double xr = obj->x_rate;
        double xo = obj->x_origin;
        double yr = obj->y_rate;
        double yo = obj->y_origin;

        double north0 = (ppt->y * yr) + yo;
        double east0 = (ppt->x * xr) + xo;
        ppt++;

        for (int ip = 1; ip < npt; ip++) {
          double north = (ppt->y * yr) + yo;
          double east = (ppt->x * xr) + xo;

          //    A slightly less coarse segment bounding box check
          if (northing >= (fmin(north, north0) - sel_rad_meters))
            if (northing <= (fmax(north, north0) + sel_rad_meters))
              if (easting >= (fmin(east, east0) - sel_rad_meters))
                if (easting <= (fmax(east, east0) + sel_rad_meters)) {
                  return true;
                }

          north0 = north;
          east0 = east;
          ppt++;
        }
      } else {  // in oSENC V2, Array of points is stored in prearranged VBO
                // array.
        if (obj->m_ls_list) {
          float *ppt;
          unsigned char *vbo_point =
              (unsigned char *)obj->m_chart_context
                  ->vertex_buffer;  // chart->GetLineVertexBuffer();
          line_segment_element *ls = obj->m_ls_list;

          while (ls && vbo_point) {
            int nPoints;
            if ((ls->ls_type == TYPE_EE) || (ls->ls_type == TYPE_EE_REV)) {
              ppt = (float *)(vbo_point + ls->pedge->vbo_offset);
              nPoints = ls->pedge->nCount;
            } else {
              ppt = (float *)(vbo_point + ls->pcs->vbo_offset);
              nPoints = 2;
            }

            float north0 = ppt[1];
            float east0 = ppt[0];

            ppt += 2;

            for (int ip = 0; ip < nPoints - 1; ip++) {
              float north = ppt[1];
              float east = ppt[0];

              if (northing >= (fmin(north, north0) - sel_rad_meters))
                if (northing <= (fmax(north, north0) + sel_rad_meters))
                  if (easting >= (fmin(east, east0) - sel_rad_meters))
                    if (easting <= (fmax(east, east0) + sel_rad_meters)) {
                      return true;
                    }

              north0 = north;
              east0 = east;

              ppt += 2;
            }

            ls = ls->next;
          }
        }
      }

      break;
    }

    case GEO_META:
    case GEO_PRIM:

      break;
  }

  return false;
}

void s57chart::SetLinePriorities() {
  if (!ps52plib) return;

  //      If necessary.....
  //      Establish line feature rendering priorities

  if (!m_bLinePrioritySet) {
    ObjRazRules *top;
    ObjRazRules *crnt;

    for (int i = 0; i < PRIO_NUM; ++i) {
      top = razRules[i][2];  // LINES
      while (top != NULL) {
        ObjRazRules *crnt = top;
        top = top->next;
        ps52plib->SetLineFeaturePriority(crnt, i);
      }

      //    In the interest of speed, choose only the one necessary area
      //    boundary style index
      int j;
      if (ps52plib->m_nBoundaryStyle == SYMBOLIZED_BOUNDARIES)
        j = 4;
      else
        j = 3;

      top = razRules[i][j];
      while (top != NULL) {
        crnt = top;
        top = top->next;  // next object
        ps52plib->SetLineFeaturePriority(crnt, i);
      }
    }

    // Traverse the entire object list again, setting the priority of each
    // line_segment_element to the maximum priority seen for that segment
    for (int i = 0; i < PRIO_NUM; ++i) {
      for (int j = 0; j < LUPNAME_NUM; j++) {
        ObjRazRules *top = razRules[i][j];
        while (top != NULL) {
          S57Obj *obj = top->obj;

          VE_Element *pedge;
          connector_segment *pcs;
          line_segment_element *list = obj->m_ls_list;
          while (list) {
            switch (list->ls_type) {
              case TYPE_EE:
              case TYPE_EE_REV:
                pedge = list->pedge;  // (VE_Element *)list->private0;
                if (pedge) list->priority = pedge->max_priority;
                break;

              default:
                pcs = list->pcs;  //(connector_segment *)list->private0;
                if (pcs) list->priority = pcs->max_priority_cs;
                break;
            }

            list = list->next;
          }

          top = top->next;
        }
      }
    }
  }

  //      Mark the priority as set.
  //      Generally only reset by Options Dialog post processing
  m_bLinePrioritySet = true;
}

typedef struct segment_pair {
  float e0, n0, e1, n1;
} _segment_pair;

void s57chart::AssembleLineGeometry() {
  // Walk the hash tables to get the required buffer size

  //  Start with the edge hash table
  size_t nPoints = 0;
  for (const auto &it : m_ve_hash) {
    VE_Element *pedge = it.second;
    if (pedge) {
      nPoints += pedge->nCount;
    }
  }

  //    printf("time0 %f\n", sw.GetTime());

  std::map<long long, connector_segment *> ce_connector_hash;
  std::map<long long, connector_segment *> ec_connector_hash;
  std::map<long long, connector_segment *> cc_connector_hash;

  std::map<long long, connector_segment *>::iterator csit;

  int ndelta = 0;

  //  Define a vector to temporarily hold the geometry for the created pcs
  //  elements

  std::vector<segment_pair> connector_segment_vector;
  size_t seg_pair_index = 0;

  //  Get the end node connected segments.  To do this, we
  //  walk the Feature array and process each feature that potentially has a
  //  LINE type element
  for (int i = 0; i < PRIO_NUM; ++i) {
    for (int j = 0; j < LUPNAME_NUM; j++) {
      ObjRazRules *top = razRules[i][j];
      while (top != NULL) {
        S57Obj *obj = top->obj;

        if ((!obj->m_ls_list) &&
            (obj->m_n_lsindex))  // object has not been processed yet
        {
          line_segment_element list_top;
          list_top.next = 0;

          line_segment_element *le_current = &list_top;

          for (int iseg = 0; iseg < obj->m_n_lsindex; iseg++) {
            if (!obj->m_lsindex_array) continue;

            int seg_index = iseg * 3;
            int *index_run = &obj->m_lsindex_array[seg_index];

            //  Get first connected node
            unsigned int inode = *index_run++;

            //  Get the edge
            bool edge_dir = true;
            int venode = *index_run++;
            if (venode < 0) {
              venode = -venode;
              edge_dir = false;
            }

            VE_Element *pedge = 0;
            if (venode) {
              if (m_ve_hash.find(venode) != m_ve_hash.end())
                pedge = m_ve_hash[venode];
            }

            //  Get end connected node
            unsigned int enode = *index_run++;

            //  Get first connected node
            VC_Element *ipnode = 0;
            ipnode = m_vc_hash[inode];

            //  Get end connected node
            VC_Element *epnode = 0;
            epnode = m_vc_hash[enode];

            if (ipnode) {
              if (pedge && pedge->nCount) {
                //      The initial node exists and connects to the start of an
                //      edge

                long long key = ((unsigned long long)inode << 32) + venode;

                connector_segment *pcs = NULL;
                csit = ce_connector_hash.find(key);
                if (csit == ce_connector_hash.end()) {
                  ndelta += 2;
                  pcs = new connector_segment;
                  ce_connector_hash[key] = pcs;

                  // capture and store geometry
                  segment_pair pair;
                  float *ppt = ipnode->pPoint;
                  pair.e0 = *ppt++;
                  pair.n0 = *ppt;

                  if (edge_dir) {
                    pair.e1 = pedge->pPoints[0];
                    pair.n1 = pedge->pPoints[1];
                  } else {
                    int last_point_index = (pedge->nCount - 1) * 2;
                    pair.e1 = pedge->pPoints[last_point_index];
                    pair.n1 = pedge->pPoints[last_point_index + 1];
                  }

                  connector_segment_vector.push_back(pair);
                  pcs->vbo_offset = seg_pair_index;  // use temporarily
                  seg_pair_index++;

                  // calculate the centroid of this connector segment, used for
                  // viz testing
                  double lat, lon;
                  fromSM_plib((pair.e0 + pair.e1) / 2,
                                (pair.n0 + pair.n1) / 2, ref_lat, ref_lon, &lat,
                                &lon);
                  pcs->cs_lat_avg = lat;
                  pcs->cs_lon_avg = lon;

                } else
                  pcs = csit->second;

                line_segment_element *pls = new line_segment_element;
                pls->next = 0;
                //                            pls->n_points = 2;
                pls->priority = 0;
                pls->pcs = pcs;
                pls->ls_type = TYPE_CE;

                le_current->next = pls;  // hook it up
                le_current = pls;
              }
            }

            if (pedge && pedge->nCount) {
              line_segment_element *pls = new line_segment_element;
              pls->next = 0;
              //                        pls->n_points = pedge->nCount;
              pls->priority = 0;
              pls->pedge = pedge;
              pls->ls_type = TYPE_EE;
              if (!edge_dir) pls->ls_type = TYPE_EE_REV;

              le_current->next = pls;  // hook it up
              le_current = pls;

            }  // pedge

            // end node
            if (epnode) {
              if (ipnode) {
                if (pedge && pedge->nCount) {
                  long long key = ((unsigned long long)venode << 32) + enode;

                  connector_segment *pcs = NULL;
                  csit = ec_connector_hash.find(key);
                  if (csit == ec_connector_hash.end()) {
                    ndelta += 2;
                    pcs = new connector_segment;
                    ec_connector_hash[key] = pcs;

                    // capture and store geometry
                    segment_pair pair;

                    if (!edge_dir) {
                      pair.e0 = pedge->pPoints[0];
                      pair.n0 = pedge->pPoints[1];
                    } else {
                      int last_point_index = (pedge->nCount - 1) * 2;
                      pair.e0 = pedge->pPoints[last_point_index];
                      pair.n0 = pedge->pPoints[last_point_index + 1];
                    }

                    float *ppt = epnode->pPoint;
                    pair.e1 = *ppt++;
                    pair.n1 = *ppt;

                    connector_segment_vector.push_back(pair);
                    pcs->vbo_offset = seg_pair_index;  // use temporarily
                    seg_pair_index++;

                    // calculate the centroid of this connector segment, used
                    // for viz testing
                    double lat, lon;
                    fromSM_plib((pair.e0 + pair.e1) / 2,
                                  (pair.n0 + pair.n1) / 2, ref_lat, ref_lon,
                                  &lat, &lon);
                    pcs->cs_lat_avg = lat;
                    pcs->cs_lon_avg = lon;

                  } else
                    pcs = csit->second;

                  line_segment_element *pls = new line_segment_element;
                  pls->next = 0;
                  pls->priority = 0;
                  pls->pcs = pcs;
                  pls->ls_type = TYPE_EC;

                  le_current->next = pls;  // hook it up
                  le_current = pls;

                } else {
                  long long key = ((unsigned long long)inode << 32) + enode;

                  connector_segment *pcs = NULL;
                  csit = cc_connector_hash.find(key);
                  if (csit == cc_connector_hash.end()) {
                    ndelta += 2;
                    pcs = new connector_segment;
                    cc_connector_hash[key] = pcs;

                    // capture and store geometry
                    segment_pair pair;

                    float *ppt = ipnode->pPoint;
                    pair.e0 = *ppt++;
                    pair.n0 = *ppt;

                    ppt = epnode->pPoint;
                    pair.e1 = *ppt++;
                    pair.n1 = *ppt;

                    connector_segment_vector.push_back(pair);
                    pcs->vbo_offset = seg_pair_index;  // use temporarily
                    seg_pair_index++;

                    // calculate the centroid of this connector segment, used
                    // for viz testing
                    double lat, lon;
                    fromSM_plib((pair.e0 + pair.e1) / 2,
                                  (pair.n0 + pair.n1) / 2, ref_lat, ref_lon,
                                  &lat, &lon);
                    pcs->cs_lat_avg = lat;
                    pcs->cs_lon_avg = lon;

                  } else
                    pcs = csit->second;

                  line_segment_element *pls = new line_segment_element;
                  pls->next = 0;
                  pls->priority = 0;
                  pls->pcs = pcs;
                  pls->ls_type = TYPE_CC;

                  le_current->next = pls;  // hook it up
                  le_current = pls;
                }
              }
            }

          }  // for

          //  All done, so assign the list to the object
          obj->m_ls_list =
              list_top.next;  // skipping the empty first placeholder element

          //  Rarely, some objects are improperly coded, e.g. cm93
          //  If found, signal this downstream for NIL processing
          if (obj->m_ls_list == NULL) {
            obj->m_n_lsindex = 0;
          }

          // we are all finished with the line segment index array, per object
          free(obj->m_lsindex_array);
          obj->m_lsindex_array = NULL;
        }

        top = top->next;
      }
    }
  }
  //    printf("time1 %f\n", sw.GetTime());

  //  We have the total VBO point count, and a nice hashmap of the connector
  //  segments
  nPoints += ndelta;  // allow for the connector segments

  size_t vbo_byte_length = 2 * nPoints * sizeof(float);

  unsigned char *buffer_offset;
  size_t offset;

  bool grow_buffer = false;

  if (0 == m_vbo_byte_length) {
    m_line_vertex_buffer = (float *)malloc(vbo_byte_length);
    m_vbo_byte_length = vbo_byte_length;
    buffer_offset = (unsigned char *)m_line_vertex_buffer;
    offset = 0;
  } else {
    m_line_vertex_buffer = (float *)realloc(
        m_line_vertex_buffer, m_vbo_byte_length + vbo_byte_length);
    buffer_offset = (unsigned char *)m_line_vertex_buffer + m_vbo_byte_length;
    offset = m_vbo_byte_length;
    m_vbo_byte_length = m_vbo_byte_length + vbo_byte_length;
    grow_buffer = true;
  }

  float *lvr = (float *)buffer_offset;

  //      Copy and edge points as floats,
  //      and recording each segment's offset in the array
  for (const auto &it : m_ve_hash) {
    VE_Element *pedge = it.second;
    if (pedge) {
      memcpy(lvr, pedge->pPoints, pedge->nCount * 2 * sizeof(float));
      lvr += pedge->nCount * 2;

      pedge->vbo_offset = offset;
      offset += pedge->nCount * 2 * sizeof(float);
    }
    //         else
    //             int yyp = 4;        //TODO Why are zero elements being
    //             inserted into m_ve_hash?
  }

  //      Now iterate on the hashmaps, adding the connector segments in the
  //      temporary vector to the VBO buffer At the  same time, populate a
  //      vector, storing the pcs pointers to allow destruction at this class
  //      dtor. This will allow us to destroy (automatically) the pcs hashmaps,
  //      and save some storage

  for (csit = ce_connector_hash.begin(); csit != ce_connector_hash.end();
       ++csit) {
    connector_segment *pcs = csit->second;
    m_pcs_vector.push_back(pcs);

    segment_pair pair = connector_segment_vector.at(pcs->vbo_offset);
    *lvr++ = pair.e0;
    *lvr++ = pair.n0;
    *lvr++ = pair.e1;
    *lvr++ = pair.n1;

    pcs->vbo_offset = offset;
    offset += 4 * sizeof(float);
  }

  for (csit = ec_connector_hash.begin(); csit != ec_connector_hash.end();
       ++csit) {
    connector_segment *pcs = csit->second;
    m_pcs_vector.push_back(pcs);

    segment_pair pair = connector_segment_vector.at(pcs->vbo_offset);
    *lvr++ = pair.e0;
    *lvr++ = pair.n0;
    *lvr++ = pair.e1;
    *lvr++ = pair.n1;

    pcs->vbo_offset = offset;
    offset += 4 * sizeof(float);
  }

  for (csit = cc_connector_hash.begin(); csit != cc_connector_hash.end();
       ++csit) {
    connector_segment *pcs = csit->second;
    m_pcs_vector.push_back(pcs);

    segment_pair pair = connector_segment_vector.at(pcs->vbo_offset);
    *lvr++ = pair.e0;
    *lvr++ = pair.n0;
    *lvr++ = pair.e1;
    *lvr++ = pair.n1;

    pcs->vbo_offset = offset;
    offset += 4 * sizeof(float);
  }

  // And so we can empty the temp buffer
  connector_segment_vector.clear();

  // We can convert the edge hashmap to a vector, to allow  us to destroy the
  // hashmap and at the same time free up the point storage in the VE_Elements,
  // since all the points are now in the VBO buffer
  for (const auto &it : m_ve_hash) {
    VE_Element *pedge = it.second;
    if (pedge) {
      m_pve_vector.push_back(pedge);
      free(pedge->pPoints);
    }
  }
  m_ve_hash.clear();

  // and we can empty the connector hashmap,
  // and at the same time free up the point storage in the VC_Elements, since
  // all the points are now in the VBO buffer
  for (const auto &it : m_vc_hash) {
    VC_Element *pcs = it.second;
    if (pcs) free(pcs->pPoint);
    delete pcs;
  }
  m_vc_hash.clear();

#ifdef ocpnUSE_GL
  if (g_b_EnableVBO) {
    if (grow_buffer) {
      if (m_LineVBO_name > 0) {
        glDeleteBuffers(1, (GLuint *)&m_LineVBO_name);
        m_LineVBO_name = -1;
      }
    }
  }
#endif
}

