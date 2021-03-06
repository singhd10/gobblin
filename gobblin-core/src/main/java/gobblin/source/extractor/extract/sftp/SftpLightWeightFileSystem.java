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

package gobblin.source.extractor.extract.sftp;

import gobblin.configuration.State;
import gobblin.source.extractor.extract.sftp.SftpFsHelper.SftpGetMonitor;
import gobblin.source.extractor.filebased.FileBasedHelperException;
import gobblin.util.HadoopUtils;
import gobblin.util.io.SeekableFSInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BufferedFSInputStream;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.util.Progressable;

import com.google.common.collect.Lists;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;


/**
 * A {@link FileSystem} implementation that provides the {@link FileSystem} interface for an SFTP server. Uses
 * {@link SftpFsHelper} internally to connect to the SFPT server. {@link HadoopUtils#newConfiguration()}
 * <ul>
 * <li>It is the caller's responsibility to call {@link #close()} on this {@link FileSystem} to disconnect the session.
 * <li>Use {@link HadoopUtils#newConfiguration()} when creating a {@link FileSystem} with
 * {@link FileSystem#get(Configuration)}. It creates a new {@link SftpLightWeightFileSystem} everytime instead of cached
 * copy
 * </ul>
 */
public class SftpLightWeightFileSystem extends FileSystem {

  private static final URI NAME = URI.create("sftp:///");
  private SftpFsHelper fsHelper;

  private static final int DEFAULT_BUFFER_SIZE = 32 * 1024;

  private static final PathFilter VALID_PATH_FILTER = new PathFilter() {
    @Override
    public boolean accept(Path path) {
      if (path == null) {
        return false;
      }
      if (StringUtils.isBlank(path.toString())) {
        return false;
      }
      if (path.toString().equals(".")) {
        return false;
      }
      if (path.toString().equals("..")) {
        return false;
      }
      return true;
    }
  };

  @Override
  public void initialize(URI name, Configuration conf) throws IOException {
    super.initialize(name, conf);
    State state = HadoopUtils.getStateFromConf(conf);
    fsHelper = new SftpFsHelper(state);
    try {
      fsHelper.connect();
    } catch (FileBasedHelperException e) {
      throw new IOException(e);
    }
  }

  @Override
  public boolean delete(Path path) throws IOException {
    ChannelSftp channel = null;
    try {
      channel = fsHelper.getSftpChannel();
      if (getFileStatus(path).isDir()) {
        channel.rmdir(HadoopUtils.toUriPath(path));
      } else {
        channel.rm(HadoopUtils.toUriPath(path));
      }
    } catch (SftpException e) {
      throw new IOException(e);
    } finally {
      safeDisconnect(channel);
    }
    return true;
  }

  @Override
  public boolean delete(Path path, boolean recursive) throws IOException {
    return delete(path);
  }

  @Override
  public FileStatus getFileStatus(Path path) throws IOException {
    ChannelSftp channelSftp = null;
    ChannelExec channelExec1 = null;
    ChannelExec channelExec2 = null;
    try {
      channelSftp = fsHelper.getSftpChannel();
      SftpATTRS sftpAttrs = channelSftp.stat(HadoopUtils.toUriPath(path));
      FsPermission permission = new FsPermission((short) sftpAttrs.getPermissions());

      channelExec1 = fsHelper.getExecChannel("id " + sftpAttrs.getUId());
      String userName = IOUtils.toString(channelExec1.getInputStream());


      channelExec2 = fsHelper.getExecChannel("id " + sftpAttrs.getGId());
      String groupName = IOUtils.toString(channelExec2.getInputStream());

      FileStatus fs =
          new FileStatus(sftpAttrs.getSize(), sftpAttrs.isDir(), 1, 0l, (long) sftpAttrs.getMTime(),
              (long) sftpAttrs.getATime(), permission, StringUtils.trimToEmpty(userName),
              StringUtils.trimToEmpty(groupName), path);

      return fs;
    } catch (SftpException e) {
      throw new IOException(e);
    } finally {
      safeDisconnect(channelSftp);
      safeDisconnect(channelExec1);
      safeDisconnect(channelExec2);
    }

  }

  @Override
  public URI getUri() {
    return NAME;
  }

  @Override
  public Path getWorkingDirectory() {
    ChannelSftp channelSftp = null;
    try {
      channelSftp = fsHelper.getSftpChannel();
      Path workingDir = new Path(channelSftp.pwd());

      return workingDir;
    } catch (SftpException e) {
      return null;
    } finally {
      safeDisconnect(channelSftp);
    }
  }

  @Override
  public FileStatus[] listStatus(Path path) throws IOException {

    try {
      List<String> fileNames = fsHelper.ls(HadoopUtils.toUriPath(path));
      List<FileStatus> status = Lists.newArrayListWithCapacity(fileNames.size());
      for (String name : fileNames) {
        Path filePath = new Path(name);
        if (VALID_PATH_FILTER.accept(filePath)) {
          status.add(getFileStatus(new Path(path, filePath)));
        }
      }
      return status.toArray(new FileStatus[status.size()]);
    } catch (FileBasedHelperException e) {
      throw new IOException(e);
    }
  }

  @Override
  public boolean mkdirs(Path path, FsPermission permission) throws IOException {
    ChannelSftp channel = null;
    try {
      channel = fsHelper.getSftpChannel();
      channel.mkdir(HadoopUtils.toUriPath(path));
      channel.chmod((int) permission.toShort(), HadoopUtils.toUriPath(path));
    } catch (SftpException e) {
      throw new IOException(e);
    } finally {
      safeDisconnect(channel);
    }
    return true;
  }

  @Override
  public FSDataInputStream open(Path path, int bufferSize) throws IOException {
    SftpGetMonitor monitor = new SftpGetMonitor();
    try {
      ChannelSftp channelSftp = fsHelper.getSftpChannel();
      InputStream is = channelSftp.get(HadoopUtils.toUriPath(path), monitor);
      return new FSDataInputStream(new BufferedFSInputStream(new SftpFsFileInputStream(is, channelSftp), bufferSize));
    } catch (SftpException e) {
      throw new IOException(e);
    }
  }

  @Override
  public FSDataInputStream open(Path path) throws IOException {
    return open(path, DEFAULT_BUFFER_SIZE);
  }

  @Override
  public boolean rename(Path oldPath, Path newPath) throws IOException {
    ChannelSftp channelSftp = null;
    try {
      channelSftp = fsHelper.getSftpChannel();
      channelSftp.rename(HadoopUtils.toUriPath(oldPath), HadoopUtils.toUriPath(newPath));

    } catch (SftpException e) {
      throw new IOException(e);
    } finally {
      safeDisconnect(channelSftp);
    }
    return true;
  }

  @Override
  public void setWorkingDirectory(Path path) {
    ChannelSftp channelSftp = null;
    try {
      channelSftp = fsHelper.getSftpChannel();
      channelSftp.lcd(HadoopUtils.toUriPath(path));

    } catch (SftpException e) {
      throw new RuntimeException("Failed to set working directory", e);
    } finally {
      safeDisconnect(channelSftp);
    }
  }

  @Override
  public void close() {
    fsHelper.close();
  }

  @Override
  public FSDataOutputStream append(Path arg0, int arg1, Progressable arg2) throws IOException {
    throw new UnsupportedOperationException("Not implemented");
  }

  @Override
  public FSDataOutputStream create(Path arg0, FsPermission arg1, boolean arg2, int arg3, short arg4, long arg5,
      Progressable arg6) throws IOException {
    throw new UnsupportedOperationException("Not implemented");
  }

  /**
   * A {@link SeekableFSInputStream} that holds a handle on the Sftp {@link Channel} used to open the
   * {@link InputStream}. The {@link Channel} is disconnected when {@link InputStream#close()} is called.
   */
  private static class SftpFsFileInputStream extends SeekableFSInputStream {

    private final Channel channel;

    public SftpFsFileInputStream(InputStream in, Channel channel) {
      super(in);
      this.channel = channel;
    }

    @Override
    public void close() throws IOException {
      super.close();
      this.channel.disconnect();
    }
  }

  /**
   * Null safe disconnect
   */
  private static void safeDisconnect(Channel channel) {
    if (channel != null) {
      channel.disconnect();
    }
  }
}
