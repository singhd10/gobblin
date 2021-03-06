/*
* Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License"); you may not use
* this file except in compliance with the License. You may obtain a copy of the
* License at  http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software distributed
* under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
* CONDITIONS OF ANY KIND, either express or implied.
*/

package gobblin.data.management.version.finder;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

import gobblin.dataset.Dataset;
import gobblin.dataset.FileSystemDataset;
import gobblin.data.management.version.FileSystemDatasetVersion;
import gobblin.data.management.version.TimestampedDatasetVersion;
import gobblin.util.FileListUtils;


/**
 * {@link gobblin.data.management.version.finder.VersionFinder} that uses the most nested file,
 * or directory if no file exists, level modifiedTimestamp under the datasetRoot path to find
 * {@link gobblin.data.management.version.FileSystemDatasetVersion}s, and represents each version as
 * {@link gobblin.data.management.version.TimestampedDatasetVersion} using the file level path
 * and modifiedTimestamp.
 */
public class FileLevelTimestampVersionFinder implements VersionFinder<TimestampedDatasetVersion> {

  private static final Logger LOGGER = LoggerFactory.getLogger(FileLevelTimestampVersionFinder.class);
  private final FileSystem fs;

  public FileLevelTimestampVersionFinder(FileSystem fs, Properties props) {
    this.fs = fs;
  }

  @Override
  public Class<? extends FileSystemDatasetVersion> versionClass() {
    return TimestampedDatasetVersion.class;
  }

  @Override
  public Collection<TimestampedDatasetVersion> findDatasetVersions(Dataset dataset) {
    FileSystemDataset fsDataset = (FileSystemDataset) dataset;
    try {
      List<TimestampedDatasetVersion> timestampedVersions = Lists.newArrayList();
      for (FileStatus fileStatus : FileListUtils.listMostNestedPathRecursively(this.fs, fsDataset.datasetRoot())) {
        timestampedVersions.add(new TimestampedDatasetVersion(new DateTime(fileStatus.getModificationTime()),
            fileStatus.getPath()));
      }
      return timestampedVersions;
    } catch (IOException e) {
      LOGGER.warn("Failed to get ModifiedTimeStamp for candidate dataset version at " + fsDataset.datasetRoot()
          + ". Ignoring.");
      return Lists.newArrayList();
    }
  }
}
