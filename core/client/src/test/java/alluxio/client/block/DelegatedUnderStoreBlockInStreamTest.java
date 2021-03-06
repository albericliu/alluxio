/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.client.block;

import alluxio.AlluxioURI;
import alluxio.Constants;
import alluxio.client.ClientContext;
import alluxio.client.file.FileSystemContext;
import alluxio.client.file.FileSystemMasterClient;
import alluxio.client.file.FileSystemWorkerClient;
import alluxio.client.file.options.OpenUfsFileOptions;
import alluxio.client.util.ClientTestUtils;
import alluxio.util.io.BufferUtils;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Tests for the {@link DirectUnderStoreBlockInStream} class.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({FileSystemContext.class, FileSystemMasterClient.class})
public class DelegatedUnderStoreBlockInStreamTest {
  private static final long BLOCK_LENGTH = 100L;
  private static final long FILE_LENGTH = 2 * BLOCK_LENGTH;
  private static final String TEST_FILENAME = "test_filename.txt";

  /** Stream for the first block. */
  private UnderStoreBlockInStream mBlockStream;
  /** Stream for the last block. */
  private UnderStoreBlockInStream mEOFBlockStream;
  /** File backing the data. */
  private File mFile;

  @Rule
  public TemporaryFolder mFolder = new TemporaryFolder();

  /**
   * Sets up the streams before a test runs.
   */
  @Before
  public void before() throws Exception {
    ClientContext.getConf().set(Constants.USER_UFS_DELEGATION_ENABLED, "true");
    mFile = mFolder.newFile(TEST_FILENAME);
    FileOutputStream os = new FileOutputStream(mFile);
    // Create a file of 2 block sizes.
    os.write(BufferUtils.getIncreasingByteArray((int) FILE_LENGTH));
    os.close();
    FileSystemContext context = PowerMockito.mock(FileSystemContext.class);
    FileSystemWorkerClient client = PowerMockito.mock(FileSystemWorkerClient.class);
    Mockito.when(context.createWorkerClient()).thenReturn(client);
    Mockito.when(
        client.openUfsFile(Mockito.any(AlluxioURI.class), Mockito.any(OpenUfsFileOptions.class)))
        .thenReturn(1L);
    Whitebox.setInternalState(FileSystemContext.class, "INSTANCE", context);
    mBlockStream =
        UnderStoreBlockInStream.Factory.create(0, BLOCK_LENGTH, BLOCK_LENGTH,
            mFile.getAbsolutePath());
    setUnderStorageStream(mBlockStream, 0);
    mEOFBlockStream =
        UnderStoreBlockInStream.Factory.create(BLOCK_LENGTH, BLOCK_LENGTH, BLOCK_LENGTH,
            mFile.getAbsolutePath());
    setUnderStorageStream(mEOFBlockStream, 0);
  }

  /**
   * Reset the client context.
   */
  @After
  public void after() {
    ClientTestUtils.resetClientContext();
  }

  /**
   * Verifies the byte-by-byte read returns the correct data, for the first block in the file.
   *
   * @throws IOException when reading from the stream fails
   */
  @Test
  public void singleByteReadTest() throws IOException {
    singleByteReadInternal(mBlockStream, 0);
  }

  /**
   * Verifies the byte-by-byte read returns the correct data, for the last block in the file.
   *
   * @throws IOException when reading from the stream fails
   */
  @Test
  public void singleByteReadEOFTest() throws IOException {
    singleByteReadInternal(mEOFBlockStream, (int) BLOCK_LENGTH);
  }

  /**
   * Internal test case to verify byte-by-byte reading of an in stream.
   *
   * @param inStream the stream to read from
   * @param startIndex the start index of the file to read from
   * @throws IOException when reading from the stream fails
   */
  private void singleByteReadInternal(UnderStoreBlockInStream inStream, int startIndex)
      throws IOException {
    long remaining = inStream.remaining();
    for (int i = startIndex; i < startIndex + BLOCK_LENGTH; i++) {
      Assert.assertEquals(i, inStream.read());
      remaining--;
      Assert.assertEquals(remaining, inStream.remaining());
    }
    // Block in stream should be complete, and should return -1, not real data.
    Assert.assertEquals(-1, inStream.read());
    Assert.assertEquals(0, inStream.remaining());
  }

  /**
   * Tests that array read methods read the correct data, for the first block of the file.
   *
   * @throws IOException when reading from the stream fails
   */
  @Test
  public void arrayReadTest() throws IOException {
    arrayReadInternal(mBlockStream, 0);
  }

  /**
   * Tests that array read methods read the correct data, for the last block of the file.
   *
   * @throws IOException when reading from the stream fails
   */
  @Test
  public void arrayReadEOFTest() throws IOException {
    arrayReadInternal(mEOFBlockStream, (int) BLOCK_LENGTH);
  }

  /**
   * Internal test case to verify array read methods an in stream.
   *
   * @param inStream the stream to read from
   * @param startIndex the start index of the file to read from
   * @throws IOException when reading from the stream fails
   */
  private void arrayReadInternal(UnderStoreBlockInStream inStream, int startIndex)
      throws IOException {
    long remaining = inStream.remaining();
    int size = (int) BLOCK_LENGTH / 10;
    byte[] readBytes = new byte[size];

    // Read first 10 bytes
    Assert.assertEquals(size, inStream.read(readBytes));
    Assert.assertTrue(BufferUtils.equalIncreasingByteArray(startIndex, size, readBytes));
    remaining -= size;
    Assert.assertEquals(remaining, inStream.remaining());

    // Read next 10 bytes
    Assert.assertEquals(size, inStream.read(readBytes));
    Assert.assertTrue(BufferUtils.equalIncreasingByteArray(startIndex + size, size, readBytes));
    remaining -= size;
    Assert.assertEquals(remaining, inStream.remaining());

    // Read with offset and length
    Assert.assertEquals(1, inStream.read(readBytes, size - 1, 1));
    Assert.assertEquals(startIndex + size * 2, readBytes[size - 1]);
    remaining--;
    Assert.assertEquals(remaining, inStream.remaining());
  }

  /**
   * Tests the array read when completely reading the first block of the file.
   *
   * @throws IOException when reading from the stream fails
   */
  @Test
  public void arrayFullReadTest() throws IOException {
    arrayFullReadInternal(mBlockStream, 0);
  }

  /**
   * Tests the array read when completely reading the last block of the file.
   *
   * @throws IOException when reading from the stream fails
   */
  @Test
  public void arrayFullReadEOFTest() throws IOException {
    arrayFullReadInternal(mEOFBlockStream, (int) BLOCK_LENGTH);
  }

  /**
   * Internal test case to verify array read methods when fully reading a block of the in stream.
   *
   * @param inStream the stream to read from
   * @param startIndex the start index of the file to read from
   * @throws IOException when reading from the stream fails
   */
  private void arrayFullReadInternal(UnderStoreBlockInStream inStream, int startIndex)
      throws IOException {
    int size = (int) inStream.remaining();
    byte[] readBytes = new byte[size];

    // Fully read the block.
    Assert.assertEquals(size, inStream.read(readBytes));
    Assert.assertEquals(0, inStream.remaining());
    Assert.assertTrue(BufferUtils.equalIncreasingByteArray(startIndex, size, readBytes));

    // Next read should return -1, and not real data.
    Assert.assertEquals(-1, inStream.read(readBytes));
    Assert.assertEquals(0, inStream.remaining());
  }

  /**
   * Tests the {@link UnderStoreBlockInStream#skip(long)} method for the first block of the file.
   *
   * @throws IOException when an operation on the stream fails
   */
  @Test
  public void skipTest() throws IOException {
    skipInternal(mBlockStream, 0);
  }

  /**
   * Tests the {@link UnderStoreBlockInStream#skip(long)} method for the last block of the file.
   *
   * @throws IOException when an operation on the stream fails
   */
  @Test
  public void skipEOFTest() throws IOException {
    skipInternal(mEOFBlockStream, (int) BLOCK_LENGTH);
  }

  /**
   * Internal test case to verify skipping with the in stream.
   *
   * @param inStream the stream to read from
   * @param startIndex the start index of the file to read from
   * @throws IOException when reading from the stream fails
   */
  private void skipInternal(UnderStoreBlockInStream inStream, int startIndex) throws IOException {
    long remaining = inStream.remaining();
    // Skip forward
    Assert.assertEquals(10, inStream.skip(10));
    remaining -= 10;
    Assert.assertEquals(remaining, inStream.remaining());
    Assert.assertEquals(startIndex + 10, inStream.read());
    remaining--;
    Assert.assertEquals(remaining, inStream.remaining());

    // Skip 0
    Assert.assertEquals(0, inStream.skip(0));
    Assert.assertEquals(remaining, inStream.remaining());
    Assert.assertEquals(startIndex + 11, inStream.read());
    remaining--;
    Assert.assertEquals(remaining, inStream.remaining());

    // Skip to the end
    Assert.assertEquals(remaining, inStream.skip(remaining));
    Assert.assertEquals(-1, inStream.read());
    Assert.assertEquals(0, inStream.remaining());

    // Seeking past the file should not seek at all.
    Assert.assertEquals(0, inStream.skip(1));
    Assert.assertEquals(0, inStream.remaining());
  }

  /**
   * Tests the {@link UnderStoreBlockInStream#seek(long)} method for the first block of the file.
   *
   * @throws IOException when an operation on the stream fails
   */
  @Test
  public void seekTest() throws Exception {
    seekInternal(mBlockStream, 0);
  }

  /**
   * Tests the {@link UnderStoreBlockInStream#seek(long)} method for the last block of the file.
   *
   * @throws IOException when an operation on the stream fails
   */
  @Test
  public void seekEOFTest() throws Exception {
    seekInternal(mEOFBlockStream, (int) BLOCK_LENGTH);
  }

  /**
   * Internal test case to verify seeking with the in stream.
   *
   * @param inStream the stream to read from
   * @param startIndex the start index of the file to read from
   * @throws IOException when reading from the stream fails
   */
  private void seekInternal(UnderStoreBlockInStream inStream, int startIndex) throws Exception {
    long remaining = inStream.remaining();
    // Seek forward
    inStream.seek(10);
    Assert.assertEquals(remaining - 10, inStream.remaining());
    Assert.assertEquals(startIndex + 10, inStream.read());
    Assert.assertEquals(remaining - 11, inStream.remaining());

    // Seek backward
    inStream.seek(2);
    setUnderStorageStream(inStream, 2);
    Assert.assertEquals(remaining - 2, inStream.remaining());
    Assert.assertEquals(startIndex + 2, inStream.read());
    Assert.assertEquals(remaining - 3, inStream.remaining());

    // Seek to end
    inStream.seek(BLOCK_LENGTH);
    Assert.assertEquals(-1, inStream.read());
    Assert.assertEquals(0, inStream.remaining());
  }

  private void setUnderStorageStream(UnderStoreBlockInStream stream, long pos) throws Exception {
    InputStream in = new FileInputStream(mFile);
    in.skip(stream.mInitPos + pos);
    Whitebox.setInternalState(stream, "mUnderStoreStream", in);
  }
}
