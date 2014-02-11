/*
 * Copyright (c) 1998 - 2011. University Corporation for Atmospheric Research/Unidata
 * Portions of this software were developed by the Unidata Program at the
 * University Corporation for Atmospheric Research.
 *
 * Access and use of this software shall impose the following obligations
 * and understandings on the user. The user is granted the right, without
 * any fee or cost, to use, copy, modify, alter, enhance and distribute
 * this software, and any derivative works thereof, and its supporting
 * documentation for any purpose whatsoever, provided that this entire
 * notice appears in all copies of the software, derivative works and
 * supporting documentation.  Further, UCAR requests that the user credit
 * UCAR/Unidata in any publications that result from the use of this
 * software or in any product that includes this software. The names UCAR
 * and/or Unidata, however, may not be used in any advertising or publicity
 * to endorse or promote any products or commercial entity unless specific
 * written permission is obtained from UCAR/Unidata. The user also
 * understands that UCAR/Unidata is not obligated to provide the user with
 * any support, consulting, training or assistance of any kind with regard
 * to the use, operation and performance of this software nor to provide
 * the user with any updates, revisions, new versions or "bug fixes."
 *
 * THIS SOFTWARE IS PROVIDED BY UCAR/UNIDATA "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL UCAR/UNIDATA BE LIABLE FOR ANY SPECIAL,
 * INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING
 * FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
 * NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION
 * WITH THE ACCESS, USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package ucar.nc2.grib.collection;

import com.google.protobuf.ByteString;
import thredds.inventory.*;
import thredds.inventory.partition.PartitionManager;
import ucar.sparr.Coordinate;
import ucar.sparr.SparseArray;
import ucar.nc2.constants.CDM;
import ucar.nc2.grib.*;
import ucar.nc2.grib.grib2.*;
import ucar.nc2.grib.grib2.table.Grib2Customizer;
import ucar.nc2.stream.NcStream;
import ucar.nc2.time.CalendarDate;
import ucar.unidata.io.RandomAccessFile;

import java.io.*;
import java.util.*;

/**
 * Build a GribCollection object for Grib-2 files. Manage grib collection index.
 * Covers GribCollectionProto, which serializes and deserializes.
 *
 * @author caron
 * @since 4/6/11
 */
public class Grib2CollectionWriter extends GribCollectionBuilder {
  public static final String MAGIC_START = "Grib2Collectio2Index";  // was Grib2CollectionIndex
  protected static final int minVersionSingle = 1;
  protected static final int version = 1;

  protected Grib2Customizer tables; // only gets created in makeAggGroups


  // for Grib2PartitionBuilder
 // protected Grib2CollectionWriter(PartitionManager tpc, org.slf4j.Logger logger) {
 //   super(tpc, false, logger);
 // }

  protected Grib2CollectionWriter(MCollection dcm, org.slf4j.Logger logger) {
    super(dcm, false, logger);
    // this.name = dcm.getCollectionName();
    //this.directory = new File(dcm.getRoot());
  }

  public static class Group {
    public Grib2SectionGridDefinition gdss;
    public int gdsHash; // may have been modified
    public CalendarDate runtime;

    public Grib2Rectilyser rect;
    public List<Grib2Record> records = new ArrayList<>();
    public String nameOverride;
    public Set<Integer> fileSet; // this is so we can show just the component files that are in this group

    Group(Grib2SectionGridDefinition gdss, int gdsHash) {
      this.gdss = gdss;
      this.gdsHash = gdsHash;
    }

    Group(Grib2SectionGridDefinition gdss, int gdsHash, CalendarDate runtime) {
      this.gdss = gdss;
      this.gdsHash = gdsHash;
      this.runtime = runtime;
    }
  }

  ///////////////////////////////////////////////////
  // heres where the actual writing is

  /*
   MAGIC_START
   version
   sizeRecords
   SparseArray's (sizeRecords bytes)
   sizeIndex
   GribCollectionIndex (sizeIndex bytes)
   */

  public boolean writeIndex(String name, File indexFile, List<Group> groups, List<MFile> files) throws IOException {
    Grib2Record first = null; // take global metadata from here
    boolean deleteOnClose = false;

    if (indexFile.exists()) {
      if (!indexFile.delete()) {
        logger.error("gc2 cant delete index file {}", indexFile.getPath());
      }
    }
    logger.debug(" createIndex for {}", indexFile.getPath());

    try (RandomAccessFile raf = new RandomAccessFile(indexFile.getPath(), "rw")) {
      //// header message
      raf.order(RandomAccessFile.BIG_ENDIAN);
      raf.write(MAGIC_START.getBytes(CDM.utf8Charset));
      raf.writeInt(version);
      long lenPos = raf.getFilePointer();
      raf.writeLong(0); // save space to write the length of the record section
      long countBytes = 0;
      int countRecords = 0;

      Set<Integer> allFileSet = new HashSet<>();
      for (Group g : groups) {
        g.fileSet = new HashSet<Integer>();
        for (Grib2Rectilyser.VariableBag vb : g.rect.getGribvars()) {
          if (first == null) first = vb.first;
          GribCollectionProto.SparseArray vr = writeSparseArray(vb, g.fileSet);
          byte[] b = vr.toByteArray();
          vb.pos = raf.getFilePointer();
          vb.length = b.length;
          raf.write(b);
          countBytes += b.length;
          countRecords += vb.coordND.getSparseArray().countNotMissing();
        }
        for (int index : g.fileSet) allFileSet.add(index);
      }

      if (logger.isDebugEnabled()) {
        long bytesPerRecord = countBytes / ((countRecords == 0) ? 1 : countRecords);
        logger.debug("  write RecordMaps: bytes = {} record = {} bytesPerRecord={}", countBytes, countRecords, bytesPerRecord);
      }

      if (first == null) {
        deleteOnClose = true;
        logger.error("GribCollection {}: has no files", name);
        throw new IOException("GribCollection " + name + " has no files");
      }

      long pos = raf.getFilePointer();
      raf.seek(lenPos);
      raf.writeLong(countBytes);
      raf.seek(pos); // back to the output.

      /*
      message GribCollection {
        required string name = 1;         // must be unique - index filename is name.ncx
        required string topDir = 2;       // filenames are reletive to this
        repeated MFile mfiles = 3;        // list of grib MFiles
        repeated Dataset dataset = 4;
        repeated Gds gds = 5;             // unique Gds, shared amongst datasets

        required int32 center = 6;      // these 4 fields are to get a GribTable object
        required int32 subcenter = 7;
        required int32 master = 8;
        required int32 local = 9;       // grib1 table Version

        optional int32 genProcessType = 10;
        optional int32 genProcessId = 11;
        optional int32 backProcessId = 12;

        repeated Parameter params = 20;      // not used yet

        extensions 100 to 199;
      }
      message GribCollection {
        required string name = 1;       // must be unique - index filename is name.ncx
        required string topDir = 2;   // filenames are reletive to this
        repeated MFile mfiles = 3;    // list of grib MFiles
        repeated Group groups = 4;      // separate group for each GDS

        required int32 center = 6;      // these 4 fields are to get a GribTable object
        required int32 subcenter = 7;
        required int32 master = 8;
        required int32 local = 9;       // grib1 table Version

        optional int32 genProcessType = 10;   // why ??
        optional int32 genProcessId = 11;
        optional int32 backProcessId = 12;

        extensions 100 to 199;
      }
       */
      GribCollectionProto.GribCollection.Builder indexBuilder = GribCollectionProto.GribCollection.newBuilder();
      indexBuilder.setName(name);
      indexBuilder.setTopDir(dcm.getRoot());

      // directory and mfile list
      File directory = new File(dcm.getRoot());
      List<GribCollectionBuilder.GcMFile> gcmfiles = GribCollectionBuilder.makeFiles(directory, files, allFileSet);
      for (GribCollectionBuilder.GcMFile gcmfile : gcmfiles) {
        GribCollectionProto.MFile.Builder b = GribCollectionProto.MFile.newBuilder();
        b.setFilename(gcmfile.getName());
        b.setLastModified(gcmfile.getLastModified());
        b.setIndex(gcmfile.index);
        indexBuilder.addMfiles(b.build());
      }

       //gds
      for (Group g : groups)
        indexBuilder.addGds(writeGdsProto(g.gdsHash, g.gdss.getRawBytes(), g.nameOverride));

      // the GC dataset
      indexBuilder.addDataset(writeDatasetProto(GribCollectionProto.Dataset.Type.GC, groups));

      // what about just storing first ??
      Grib2SectionIdentification ids = first.getId();
      indexBuilder.setCenter(ids.getCenter_id());
      indexBuilder.setSubcenter(ids.getSubcenter_id());
      indexBuilder.setMaster(ids.getMaster_table_version());
      indexBuilder.setLocal(ids.getLocal_table_version());

      Grib2Pds pds = first.getPDS();
      indexBuilder.setGenProcessType(pds.getGenProcessType());
      indexBuilder.setGenProcessId(pds.getGenProcessId());
      indexBuilder.setBackProcessId(pds.getBackProcessId());

      GribCollectionProto.GribCollection index = indexBuilder.build();
      byte[] b = index.toByteArray();
      NcStream.writeVInt(raf, b.length); // message size
      raf.write(b);  // message  - all in one gulp
      logger.debug("  write GribCollectionIndex= {} bytes", b.length);

    } finally {
      // remove it on failure
      if (deleteOnClose && !indexFile.delete())
        logger.error(" gc2 cant deleteOnClose index file {}", indexFile.getPath());
    }

    return true;
  }

  /*
  message Record {
    required uint32 fileno = 1;  // index into GribCollectionIndex.files
    required uint64 pos = 2;     // offset in Grib file of the start of drs (grib2) or entire message (grib1)
    optional uint64 bmsPos = 3 [default = 0]; // use alternate bms
  }

  // dont need SparseArray unless someone wants to read from the variable
  message SparseArray {
    required fixed32 cdmHash = 1; // which variable
    repeated uint32 size = 2;     // multidim sizes
    repeated uint32 track = 3;    // 1-based index into record list, 0 == missing
    repeated Record records = 4;  // List<Record>
  }
   */
  private GribCollectionProto.SparseArray writeSparseArray(Grib2Rectilyser.VariableBag vb, Set<Integer> fileSet) throws IOException {
    GribCollectionProto.SparseArray.Builder b = GribCollectionProto.SparseArray.newBuilder();
    b.setCdmHash(vb.cdmHash);
    SparseArray<Grib2Record> sa = vb.coordND.getSparseArray();
    for (int size : sa.getShape())
      b.addSize(size);
    for (int track : sa.getTrack())
      b.addTrack(track);

    for (Grib2Record gr : sa.getContent()) {
      GribCollectionProto.Record.Builder br = GribCollectionProto.Record.newBuilder();

      br.setFileno(gr.getFile());
      fileSet.add(gr.getFile());
      Grib2SectionDataRepresentation drs = gr.getDataRepresentationSection();
      br.setPos(drs.getStartingPosition());
      if (gr.isBmsReplaced()) {
        Grib2SectionBitMap bms = gr.getBitmapSection();
        br.setBmsPos(bms.getStartingPosition());
      }
      br.setScanMode(gr.getScanMode()); // added 2/6/2014
      b.addRecords(br);
    }
    return b.build();
  }


  protected GribCollectionProto.Gds writeGdsProto(GribCollection.HorizCoordSys hcs) throws IOException {
    return writeGdsProto(hcs.getGdsHash(), hcs.getRawGds(), hcs.getNameOverride());
  }

    /*
  message Gds {
    optional bytes gds = 1;             // all variables in the group use the same GDS
    optional sint32 gdsHash = 2 [default = 0];
    optional string nameOverride = 3;  // only when user overrides default name
  }
   */
  protected GribCollectionProto.Gds writeGdsProto(int gdsHash, byte[] rawGds, String nameOverride) throws IOException {
    GribCollectionProto.Gds.Builder b = GribCollectionProto.Gds.newBuilder();

    b.setGds(ByteString.copyFrom(rawGds));
    b.setGdsHash(gdsHash);
    if (nameOverride != null)
      b.setNameOverride(nameOverride);

    return b.build();
  }

  /*
  message Dataset {
    required Type type = 1;
    repeated Group groups = 2;
   */
  private GribCollectionProto.Dataset writeDatasetProto(GribCollectionProto.Dataset.Type type, List<Group> groups) throws IOException {
    GribCollectionProto.Dataset.Builder b = GribCollectionProto.Dataset.newBuilder();

    b.setType(type);

    int count = 0 ;
    for (Group group : groups)
      b.addGroups(writeGroupProto(group, count++));

    return b.build();
  }

  /*
  message Group {
    required uint32 gdsIndex = 1;       // index into GribCollection.gds array
    repeated Variable variables = 2;    // list of variables
    repeated Coord coords = 3;          // list of coordinates
    repeated int32 fileno = 4;          // the component files that are in this group, index into gc.mfiles

    repeated Parameter params = 20;      // not used yet
    extensions 100 to 199;
  }
   */
  protected GribCollectionProto.Group writeGroupProto(Group g, int groupIndex) throws IOException {
    GribCollectionProto.Group.Builder b = GribCollectionProto.Group.newBuilder();

    b.setGdsIndex(groupIndex); // index into gds list
    b.setIsTwod(true);         // LOOK

    for (Grib2Rectilyser.VariableBag vbag : g.rect.getGribvars()) {
      b.addVariables(writeVariableProto(vbag));
    }

    for (Coordinate coord : g.rect.getCoordinates()) {
      switch (coord.getType()) {
        case runtime:
          b.addCoords(writeCoordProto((CoordinateRuntime) coord));
          break;
        case time:
          b.addCoords(writeCoordProto((CoordinateTime) coord));
          break;
        case timeIntv:
          b.addCoords(writeCoordProto((CoordinateTimeIntv) coord));
          break;
        case vert:
          b.addCoords(writeCoordProto((CoordinateVert) coord));
          break;
        case time2D:
          b.addCoords(writeCoordProto((CoordinateTime2D) coord));
          break;
      }
    }

    for (Integer aFileSet : g.fileSet)
      b.addFileno(aFileSet);

    return b.build();
  }

  /*
  message Variable {
     required uint32 discipline = 1;
     required bytes pds = 2;          // raw pds
     required fixed32 cdmHash = 3;

     required uint64 recordsPos = 4;  // offset of SparseArray message for this Variable
     required uint32 recordsLen = 5;  // size of SparseArray message for this Variable

     repeated uint32 coordIdx = 6;    // indexes into Group.coords

     // optionally keep stats
     optional float density = 7;
     optional uint32 ndups = 8;
     optional uint32 nrecords = 9;
     optional uint32 missing = 10;

     repeated uint32 invCount = 15;      // for Coordinate TwoTimer, only 2D vars
     repeated uint32 time2runtime = 16;  // time index to runtime index, only 1D vars
     repeated Parameter params = 20;    // not used yet

     extensions 100 to 199;
   }
   */
  private GribCollectionProto.Variable writeVariableProto(Grib2Rectilyser.VariableBag vb) throws IOException {
    GribCollectionProto.Variable.Builder b = GribCollectionProto.Variable.newBuilder();

    b.setDiscipline(vb.first.getDiscipline());
    b.setPds(ByteString.copyFrom(vb.first.getPDSsection().getRawBytes()));
    b.setCdmHash(vb.cdmHash);

    b.setRecordsPos(vb.pos);
    b.setRecordsLen(vb.length);

    for (int idx : vb.coordIndex)
      b.addCoordIdx(idx);

    // keep stats
    SparseArray sa = vb.coordND.getSparseArray();
    if (sa != null) {
      b.setDensity(sa.getDensity());
      b.setNdups(sa.getNduplicates());
      b.setNrecords(sa.countNotMissing());
      b.setMissing(sa.countMissing());
    }

    // LOOK
    /* if (vp.twot != null) { // only for 2D
      List<Integer> invCountList = new ArrayList<>(vp.twot.getCount().length);
      for (int count : vp.twot.getCount()) invCountList.add(count);
      b.setExtension(PartitionCollectionProto.invCount, invCountList);
    }

    if (vp.time2runtime != null) { // only for 1D
      List<Integer> list = new ArrayList<>(vp.time2runtime.length);
      for (int idx : vp.time2runtime) list.add(idx);
      b.setExtension(PartitionCollectionProto.time2Runtime, list);
    } */

    return b.build();
  }

  /*
  message Coord {
    required int32 type = 1;   // Coordinate.Type.oridinal
    required int32 code = 2;   // time unit; level type
    required string unit = 3;
    repeated float values = 4;
    repeated float bound = 5; // only used if interval, then = (value, bound)
    repeated int64 msecs = 6; // calendar date
   */
  protected GribCollectionProto.Coord writeCoordProto(CoordinateRuntime coord) throws IOException {
    GribCollectionProto.Coord.Builder b = GribCollectionProto.Coord.newBuilder();
    b.setType(coord.getType().ordinal());
    b.setCode(coord.getCode());
    if (coord.getUnit() != null) b.setUnit(coord.getUnit());
    for (CalendarDate cd : coord.getRuntimesSorted()) {
      b.addMsecs(cd.getMillis());
    }
    return b.build();
  }

  protected GribCollectionProto.Coord writeCoordProto(CoordinateTime coord) throws IOException {
    GribCollectionProto.Coord.Builder b = GribCollectionProto.Coord.newBuilder();
    b.setType(coord.getType().ordinal());
    b.setCode(coord.getCode());
    b.setUnit(coord.getTimeUnit().toString());
    b.addMsecs(coord.getRefDate().getMillis());
    for (Integer offset : coord.getOffsetSorted()) {
      b.addValues(offset);
    }
    return b.build();
  }

  protected GribCollectionProto.Coord writeCoordProto(CoordinateTimeIntv coord) throws IOException {
    GribCollectionProto.Coord.Builder b = GribCollectionProto.Coord.newBuilder();
    b.setType(coord.getType().ordinal());
    b.setCode(coord.getCode());
    b.setUnit(coord.getTimeUnit().toString());
    b.addMsecs(coord.getRefDate().getMillis());

    // LOOK old way - do we need ?
    /*     float scale = (float) tc.getTimeUnitScale(); // deal with, eg, "6 hours" by multiplying values by 6
        if (tc.isInterval()) {
          for (TimeCoord.Tinv tinv : tc.getIntervals()) {
            b.addValues(tinv.getBounds1() * scale);
            b.addBound(tinv.getBounds2() * scale);
          } */
    for (TimeCoord.Tinv tinv : coord.getTimeIntervals()) {
      b.addValues(tinv.getBounds1());
      b.addBound(tinv.getBounds2());
    }
    return b.build();
  }

  protected GribCollectionProto.Coord writeCoordProto(CoordinateVert coord) throws IOException {
    GribCollectionProto.Coord.Builder b = GribCollectionProto.Coord.newBuilder();
    b.setType(coord.getType().ordinal());
    b.setCode(coord.getCode());

    //VertCoord.VertUnit vertUnit = Grib2Utils.getLevelUnit(coord.getCode());

    if (coord.getUnit() != null) b.setUnit(coord.getUnit());
    for (VertCoord.Level level : coord.getLevelSorted()) {
      if (coord.isLayer()) {
        b.addValues((float) level.getValue1());
        b.addBound((float) level.getValue2());
      } else {
        b.addValues((float) level.getValue1());
      }
    }
    return b.build();
  }

  protected GribCollectionProto.Coord writeCoordProto(CoordinateTime2D coord) throws IOException {
    GribCollectionProto.Coord.Builder b = GribCollectionProto.Coord.newBuilder();
    b.setType(coord.getType().ordinal());
    b.setCode(coord.getCode());
    b.setUnit(coord.getTimeUnit().toString());
    CoordinateRuntime runtime = coord.getRuntimeCoordinate();
    for (CalendarDate cd : runtime.getRuntimesSorted()) {
      b.addMsecs(cd.getMillis());
    }

    for (Coordinate time : coord.getTimes()) {
      if (time.getType() == Coordinate.Type.time)
        b.addTimes(writeCoordProto((CoordinateTime)time));
      else
        b.addTimes(writeCoordProto((CoordinateTimeIntv)time));
    }

    return b.build();
  }



}
