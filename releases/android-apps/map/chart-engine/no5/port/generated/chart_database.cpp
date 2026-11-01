#include "chart_database.hpp"
#include <unistd.h>
thread_local ChartDB* ChartData=nullptr;
#ifdef __ANDROID__
bool androidIsDirWritable(wxString path){return access(path.c_str(),W_OK)==0;}
#endif

inline int CCW(MyFlPoint p0, MyFlPoint p1, MyFlPoint p2) {
  float dx1, dx2;
  float dy1, dy2;

  dx1 = p1.x - p0.x;
  dx2 = p2.x - p0.x;
  dy1 = p1.y - p0.y;
  dy2 = p2.y - p0.y;

  /* This is basically a slope comparison: we don't do divisions because

   * of divide by zero possibilities with pure horizontal and pure
   * vertical lines.
   */
  return ((dx1 * dy2 > dy1 * dx2) ? 1 : -1);
}

inline bool Intersect(MyFlPoint p1, MyFlPoint p2, MyFlPoint p3, MyFlPoint p4) {
  return (((CCW(p1, p2, p3) * CCW(p1, p2, p4)) <= 0) &&
          ((CCW(p3, p4, p1) * CCW(p3, p4, p2) <= 0)));
}

bool G_FloatPtInPolygon(MyFlPoint *rgpts, int wnumpts, float x, float y)

{
  MyFlPoint *ppt, *ppt1;
  int i;
  MyFlPoint pt1, pt2, pt0;
  int wnumintsct = 0;

  pt0.x = x;
  pt0.y = y;

  pt1 = pt2 = pt0;
  pt2.x = 1.e6;

  // Now go through each of the lines in the polygon and see if it
  // intersects
  for (i = 0, ppt = rgpts; i < wnumpts - 1; i++, ppt++) {
    ppt1 = ppt;
    ppt1++;
    if (Intersect(pt0, pt2, *ppt, *(ppt1))) wnumintsct++;
  }

  // And the last line
  if (Intersect(pt0, pt2, *ppt, *rgpts)) wnumintsct++;

  //   return(wnumintsct&1);

  //       If result is false, check the degenerate case where test point lies
  //       on a polygon endpoint
  if (!(wnumintsct & 1)) {
    for (i = 0, ppt = rgpts; i < wnumpts; i++, ppt++) {
      if (((*ppt).x == x) && ((*ppt).y == y)) return true;
    }
  } else
    return true;

  return false;
}

int ChartStack::GetCurrentEntrydbIndex() {
  if (nEntry && (CurrentStackEntry >= 0) /*&& b_valid*/)
    return DBIndex[CurrentStackEntry];
  else
    return -1;
}

void ChartStack::SetCurrentEntryFromdbIndex(int current_db_index) {
  for (int i = 0; i < nEntry; i++) {
    if (current_db_index == DBIndex[i]) CurrentStackEntry = i;
  }
}

int ChartStack::GetDBIndex(int stack_index) {
  if ((stack_index >= 0) && (stack_index < nEntry) && (stack_index < MAXSTACK))
    return DBIndex[stack_index];
  else
    return -1;
}

void ChartStack::SetDBIndex(int stack_index, int db_index) {
  if ((stack_index >= 0) && (stack_index < nEntry) && (stack_index < MAXSTACK))
    DBIndex[stack_index] = db_index;
}

bool ChartStack::DoesStackContaindbIndex(int db_index) {
  for (int i = 0; i < nEntry; i++) {
    if (db_index == DBIndex[i]) return true;
  }

  return false;
}

void ChartStack::AddChart(int db_add) {
  if (!ChartData) return;

  if (!ChartData->IsValid()) return;

  int db_index = db_add;

  int j = nEntry;

  if (db_index >= 0) {
    j++;
    nEntry = j;
    SetDBIndex(j - 1, db_index);
  }
  //    Remove exact duplicates, i.e. charts that have exactly the same file
  //    name and
  //     nearly the same mod time.
  //    These charts can be in the database due to having the exact same chart
  //    in different directories, as may be desired for some grouping schemes
  //    Note that if the target name is actually a directory, then windows fails
  //    to produce a valid file modification time.  Detect GetFileTime() == 0,
  //    and skip the test in this case
  for (int id = 0; id < j - 1; id++) {
    if (GetDBIndex(id) != -1) {
      auto &pm = ChartData->GetChartTableEntry(GetDBIndex(id));

      for (int jd = id + 1; jd < j; jd++) {
        if (GetDBIndex(jd) != -1) {
          auto &pn = ChartData->GetChartTableEntry(GetDBIndex(jd));
          if (pm.GetFileTime() && pn.GetFileTime()) {
            if (labs(pm.GetFileTime() - pn.GetFileTime()) <
                60) {  // simple test
              if (pn.GetpFileName()->IsSameAs(*(pm.GetpFileName())))
                SetDBIndex(jd, -1);  // mark to remove
            }
          }
        }
      }
    }
  }

  int id = 0;
  while ((id < j)) {
    if (GetDBIndex(id) == -1) {
      int jd = id + 1;
      while (jd < j) {
        int db_index = GetDBIndex(jd);
        SetDBIndex(jd - 1, db_index);
        jd++;
      }

      j--;
      nEntry = j;

      id = 0;
    } else
      id++;
  }

  //    Sort the stack on scale
  int swap = 1;
  int ti;
  while (swap == 1) {
    swap = 0;
    for (int i = 0; i < j - 1; i++) {
      const ChartTableEntry &m = ChartData->GetChartTableEntry(GetDBIndex(i));
      const ChartTableEntry &n =
          ChartData->GetChartTableEntry(GetDBIndex(i + 1));

      if (n.GetScale() < m.GetScale()) {
        ti = GetDBIndex(i);
        SetDBIndex(i, GetDBIndex(i + 1));
        SetDBIndex(i + 1, ti);
        swap = 1;
      }
    }
  }
}

int ChartDB::BuildChartStack(ChartStack *cstk, float lat, float lon,
                             int groupIndex) {
  int i = 0;
  int j = 0;

  if (!IsValid()) return 0;  // Database is not properly initialized

  if (!cstk) return 0;  // Chartstack not ready yet

  int nEntry = GetChartTableEntries();

  for (int db_index = 0; db_index < nEntry; db_index++) {
    const ChartTableEntry &cte = GetChartTableEntry(db_index);

    // Skip any charts in Exclude array
    if (IsChartDirectoryExcluded(cte.GetFullPath())) continue;

    //    Check to see if the candidate chart is in the currently active group
    bool b_group_add = false;
    if (groupIndex > 0) {
      const int ng = cte.GetGroupArray().size();
      for (int ig = 0; ig < ng; ig++) {
        if (groupIndex == cte.GetGroupArray()[ig]) {
          b_group_add = true;
          break;
        }
      }
    } else
      b_group_add = true;

    bool b_writable_add = true;
    //  On android, SDK > 29, we require that the directory of charts be
    //  "writable" as determined by Android Java file system
#ifdef __ANDROID__
    wxFileName fn(cte.GetFullSystemPath());
    if (!androidIsDirWritable(fn.GetPath())) b_writable_add = false;
#endif

    bool b_pos_add = false;
    if (b_group_add && b_writable_add) {
      //  Plugin loading is deferred, so the chart may have been disabled
      //  elsewhere. Tentatively reenable the chart so that it appears in the
      //  piano. It will get disabled later if really not useable
      if (cte.GetChartType() == CHART_TYPE_PLUGIN) {
        ChartTableEntry *pcte = (ChartTableEntry *)&cte;
        pcte->ReEnable();
      }

      if (CheckPositionWithinChart(db_index, lat, lon) && (j < MAXSTACK))
        b_pos_add = true;

      //    Check the special case where chart spans the international dateline
      else if ((cte.GetLonMax() > 180.) && (cte.GetLonMin() < 180.)) {
        if (CheckPositionWithinChart(db_index, lat, lon + 360.) &&
            (j < MAXSTACK))
          b_pos_add = true;
      }
      //    Western hemisphere, some type of charts
      else if ((cte.GetLonMax() > 180.) && (cte.GetLonMin() > 180.)) {
        if (CheckPositionWithinChart(db_index, lat, lon + 360.) &&
            (j < MAXSTACK))
          b_pos_add = true;
      }
    }

    bool b_available = true;
    //  Verify PlugIn charts are actually available
    if (b_group_add && b_pos_add && (cte.GetChartType() == CHART_TYPE_PLUGIN)) {
      ChartTableEntry *pcte = (ChartTableEntry *)&cte;
      if (!IsChartAvailable(db_index)) {
        pcte->SetAvailable(false);
        b_available = false;
      } else {
        pcte->SetAvailable(true);
        pcte->ReEnable();
      }
    }

    if (b_group_add && b_pos_add && b_available) {  // add it
      j++;
      cstk->nEntry = j;
      cstk->SetDBIndex(j - 1, db_index);
    }
  }

  cstk->nEntry = j;

  //    Remove exact duplicates, i.e. charts that have exactly the same file
  //    name and nearly the same mod time These charts can be in the database
  //    due to having the exact same chart in different directories, as may be
  //    desired for some grouping schemes Note that if the target name is
  //    actually a directory, then windows fails to produce a valid file
  //    modification time.  Detect GetFileTime() == 0, and skip the test in this
  //    case
  //    Extended to also check for "identical" charts, having exact same
  //    EditionDate

  for (int id = 0; id < j - 1; id++) {
    if (cstk->GetDBIndex(id) != -1) {
      const ChartTableEntry &ctem = GetChartTableEntry(cstk->GetDBIndex(id));

      for (int jd = id + 1; jd < j; jd++) {
        if (cstk->GetDBIndex(jd) != -1) {
          const ChartTableEntry &cten =
              GetChartTableEntry(cstk->GetDBIndex(jd));
          bool bsameTime = false;
          if (ctem.GetFileTime() && cten.GetFileTime()) {
            if (labs(ctem.GetFileTime() - cten.GetFileTime()) < 60)
              bsameTime = true;
          }
          if (ctem.GetChartEditionDate() == cten.GetChartEditionDate())
            bsameTime = true;

          if (bsameTime) {
            if (cten.GetpFileName()->IsSameAs(*(ctem.GetpFileName())))
              cstk->SetDBIndex(jd, -1);  // mark to remove
          }
        }
      }
    }
  }

  int id = 0;
  while ((id < j)) {
    if (cstk->GetDBIndex(id) == -1) {
      int jd = id + 1;
      while (jd < j) {
        int db_index = cstk->GetDBIndex(jd);
        cstk->SetDBIndex(jd - 1, db_index);
        jd++;
      }

      j--;
      cstk->nEntry = j;

      id = 0;
    } else
      id++;
  }

  //    Sort the stack on scale
  int swap = 1;
  int ti;
  while (swap == 1) {
    swap = 0;
    for (i = 0; i < j - 1; i++) {
      const ChartTableEntry &m = GetChartTableEntry(cstk->GetDBIndex(i));
      const ChartTableEntry &n = GetChartTableEntry(cstk->GetDBIndex(i + 1));

      if (n.GetScale() < m.GetScale()) {
        ti = cstk->GetDBIndex(i);
        cstk->SetDBIndex(i, cstk->GetDBIndex(i + 1));
        cstk->SetDBIndex(i + 1, ti);
        swap = 1;
      }
    }
  }

  cstk->b_valid = true;

  return j;
}

bool ChartDB::CheckPositionWithinChart(int index, float lat, float lon) {
  const ChartTableEntry *pt = &GetChartTableEntry(index);

  //    First check on rough Bounding box

  if ((lat <= pt->GetLatMax()) && (lat >= pt->GetLatMin()) &&
      (lon >= pt->GetLonMin()) && (lon <= pt->GetLonMax())) {
    for (const auto& polygon : pt->exclusions) {
      if (G_FloatPtInPolygon((MyFlPoint*)polygon.data(), polygon.size()/2, lon, lat))
        return false;
    }
    //    Double check on Primary Ply points polygon

    bool bInside = G_FloatPtInPolygon((MyFlPoint *)pt->GetpPlyTable(),
                                      pt->GetnPlyEntries(), lon, lat);

    if (bInside) {
      if (pt->GetnAuxPlyEntries()) {
        for (int k = 0; k < pt->GetnAuxPlyEntries(); k++) {
          bool bAuxInside =
              G_FloatPtInPolygon((MyFlPoint *)pt->GetpAuxPlyTableEntry(k),
                                 pt->GetAuxCntTableEntry(k), lon, lat);
          if (bAuxInside) return true;
          ;
        }

      } else
        return true;
    }
  }

  return false;
}

bool ChartDB::EqualStacks(ChartStack *pa, ChartStack *pb) {
  if ((pa == 0) || (pb == 0)) return false;
  if ((!pa->b_valid) || (!pb->b_valid)) return false;
  if (pa->nEntry != pb->nEntry) return false;

  for (int i = 0; i < pa->nEntry; i++) {
    if (pa->GetDBIndex(i) != pb->GetDBIndex(i)) return false;
  }

  return true;
}
