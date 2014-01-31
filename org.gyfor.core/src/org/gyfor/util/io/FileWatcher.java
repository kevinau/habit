package org.gyfor.util.io;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;


/**
 * Example to watch a directory (or tree) for changes to files.
 */
public class FileWatcher {

  public static interface IProcessor {
    
    public void process (WatchEvent.Kind<?> kind, File file);

  }

  
  private static final int WAIT_TIME = 1000;
  
  private final WatchService watcher;
  private final IProcessor processor;
  private final Map<WatchKey, Path> keys;
  private final boolean recursive;
  private final File dir;
  private final String filePattern;
  private final PathMatcher fileMatcher;

  private List<PathMatcher> ignores;
  {
    ignores = new ArrayList<>();
    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:*");
    ignores.add(matcher);
  }
  
  
  private final Timer timer = new Timer();
  
  private final List<String> waitList = new ArrayList<>();
  

  @SuppressWarnings("unchecked")
  static <T> WatchEvent<T> cast(WatchEvent<?> event) {
    return (WatchEvent<T>)event;
  }

  /**
   * Register the given directory with the WatchService
   */
  private void register(Path dir) {
    try {
      WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
      keys.put(key, dir);
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  /**
   * Register the given directory, and all its sub-directories, with the
   * WatchService.
   */
  private void registerAll(final Path start) {
    try {
      // register directory and sub-directories
      Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
          register(dir);
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  
  public FileWatcher(File dir, String filePattern, IProcessor processor) {
    this (dir, filePattern, processor, false);
  }
  
  
  /**
   * Creates a WatchService and registers the given directory
   */
  public FileWatcher(File dir, String filePattern, IProcessor processor, boolean recursive) {
    try {
      this.watcher = FileSystems.getDefault().newWatchService();
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
    this.keys = new HashMap<WatchKey, Path>();
    this.recursive = recursive;
    this.dir = dir;
    if (!(filePattern.startsWith("glob:") || filePattern.startsWith("regex:"))) {
      filePattern = "glob:" + filePattern;
    }
    this.filePattern = filePattern;
    this.fileMatcher = FileSystems.getDefault().getPathMatcher(this.filePattern);
    if (processor == null) {
      throw new IllegalArgumentException("IFileProcessor cannot be null");
    }
    this.processor = processor;
  }
  
  
  public void register () {
    Path path = dir.toPath();
    if (recursive) {
      registerAll(path);
    } else {
      register(path);
    }
  }


  private static class WaitStableTask extends TimerTask {

    private final FileWatcher fileFinder;
    private final WatchEvent.Kind<?> kind;
    private final File file;
    private final long lastLength;
    
    private WaitStableTask (FileWatcher fileFinder, WatchEvent.Kind<?> kind, File file, long lastLength) {
      this.fileFinder = fileFinder;
      this.kind = kind;
      this.file = file;
      this.lastLength = lastLength;
    }
    
    @Override
    public void run() {
      long currLength;
      if (file.exists()) {
        currLength = file.length();
      } else {
        currLength = -1;
      }
      if (currLength == lastLength) {
        synchronized (fileFinder.waitList) {
          fileFinder.waitList.remove(file.getName());
        }
        if (kind == ENTRY_CREATE && currLength == -1) {
          fileFinder.processor.process(kind, file);
          fileFinder.processor.process(ENTRY_DELETE, file);
        } else if (kind == ENTRY_DELETE && currLength != -1) {
          fileFinder.processor.process(kind, file);
          fileFinder.processor.process(ENTRY_CREATE, file);
        } else {
          fileFinder.processor.process(kind, file);
        }
      } else {
        fileFinder.timer.schedule(new WaitStableTask(fileFinder, kind, file, currLength), WAIT_TIME);
      }
    }
    
  }
  
  
  /**
   * Process all events for keys queued to the watcher
   */
  public void processEvents() {
    for (;;) {

      // wait for key to be signalled
      WatchKey key;
      try {
        key = watcher.take();
      } catch (ClosedWatchServiceException ex) {
        return;
      } catch (InterruptedException xx) {
        return;
      }

      Path dir = keys.get(key);
      if (dir == null) {
        System.err.println("WatchKey not recognized!!");
        continue;
      }

      for (WatchEvent<?> event : key.pollEvents()) {
        WatchEvent.Kind<?> kind = event.kind();

        // TBD - provide example of how OVERFLOW event is handled
        if (kind == OVERFLOW) {
          continue;
        }

        // Context for directory entry event is the file name of entry
        WatchEvent<Path> ev = cast(event);
        Path name = ev.context();
        Path child = dir.resolve(name);
        String childName = child.getFileName().toString();

        // Is it a file we are interested in
        if (fileMatcher.matches(child.getFileName())) {
          synchronized (waitList) {
            if (!waitList.contains(childName)) {
              waitList.add(childName);
    
              // Wait for file event to be stable
              long size;
              if (kind == ENTRY_DELETE) {
                size = -1;
              } else {
                size = child.toFile().length();
              }
              timer.schedule(new WaitStableTask(this, kind, child.toFile(), size), WAIT_TIME); 
            }
          }
        }
        
        // if directory is created, and watching recursively, then
        // register it and its sub-directories
        if (recursive && (kind == ENTRY_CREATE)) {
          if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
            registerAll(child);
          }
        }
      }

      // reset key and remove from set if directory no longer accessible
      boolean valid = key.reset();
      if (!valid) {
        keys.remove(key);

        // all directories are inaccessible
        if (keys.isEmpty()) {
          break;
        }
      }
    }
  }
  
  
//  private void prime () {
//    PathMatcher matcher = FileSystems.getDefault().getPathMatcher(filePattern);
//    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir.toPath())) {
//      for (Path entry : stream) {
//        Path filePath = entry.getFileName();
//        if (matcher.matches(filePath)) {
//          waitList.add(filePath.toString());
//          // Wait for file event to be stable
//          long size = entry.toFile().length();
//          timer.schedule(new WaitStableTask(this, ENTRY_CREATE, entry.toFile(), size), WAIT_TIME); 
//        }
//      }
//    } catch (IOException ex) {
//      throw new RuntimeException(ex);
//    }
//  }
  
  
  private List<PathMatcher> loadIgnores () {
    List<PathMatcher> ignores = new ArrayList<>();
    File ignoreFile = new File(dir, ".ignore.txt");
    if (ignoreFile.exists()) {
      try (BufferedReader reader = new BufferedReader(new FileReader(ignoreFile))) {
        String line = reader.readLine();
        while (line != null) {
          if (!line.startsWith("glob:") && !line.startsWith("grep:")) {
            line = "glob:" + line;
          }
          PathMatcher matcher = FileSystems.getDefault().getPathMatcher(line);
          ignores.add(matcher);
          line = reader.readLine();
        }
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }
    return ignores;
  }
  
  
  private static boolean isIgnored (List<PathMatcher> ignores, Path path) {
    for (PathMatcher matcher : ignores) {
      if (matcher.matches(path)) {
        return true;
      }
    }
    return false;
  }
  
  
  private synchronized void prime2 () {
    List<PathMatcher> newIgnores = loadIgnores();
    List<String> included = new ArrayList<>();
    List<String> excluded = new ArrayList<>();
    
    PathMatcher matcher = FileSystems.getDefault().getPathMatcher(filePattern);
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir.toPath())) {
      for (Path entry : stream) {
        Path filePath = entry.getFileName();
        if (matcher.matches(filePath)) {
          if (isIgnored(ignores, filePath)) {
            if (isIgnored(newIgnores, filePath)) {
              // Ignored previously, and still ignored
            } else {
              // Ignored previously, but not ignored now, so include it
              included.add(filePath.toString());
            }
          } else {
            if (isIgnored(newIgnores, filePath)) {
              // Not ignored previously, but is now, so exclude it
              excluded.add(filePath.toString());
            } else {
              // Not ignored previously, and not ignored now, so leave it unchanged
            }
          }
        }
      }
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
    ignores = newIgnores;
    
    // Process the files now we have the lists
    for (String fileName : excluded) {
      processor.process(ENTRY_DELETE, new File(dir, fileName));
    }
    for (String fileName : included) {
      processor.process(ENTRY_CREATE, new File(dir, fileName));
    }
  }
  
  
  public void start () {
    register();
    prime2();
    Thread thread = new Thread() {
      @Override 
      public void run () {
        processEvents();
      }
    };
    thread.start();
  }


  public void close () {
    try {
      watcher.close();
    } catch (IOException e) {
      // Ignore this exception
    }
  }

}