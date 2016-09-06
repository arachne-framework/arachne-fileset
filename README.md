# arachne-fileset

A remix of Boot's filesets, intended to provide a more functional API user in a larger variety of settings. Many thanks to Alan Dipert and Micha Niskin for the original version in Boot's source code, and for their assistance in understanding it.
 
## Usage

A *fileset* is an immutable data structure representing a logical filesystem directory, containing some number of files. Filesets are immutable persistent data structures; you can obtain a new fileset by adding, removing or modifying files without altering the original fileset instance in any way. They also support correct equality semantics, based on the hashed content and timestamps of the files they contain.

#### Creating a fileset

Use the `fileset` function with no aguments to create a new fileset. The fileset will be empty, containing no files.

#### Adding files to a fileset

Use the `add` function to add all the files in a normal filesystem directory to the fileset. Under the hood, this actually copies the files, so once they are added changes to the filesystem will not affect the fileset.

```clojure
(require '[arachne.fileset :as fs])
(require '[clojure.java.io :as io])

(def my-fileset (fs/add (fs/fileset) (io/file "some/directory")))
```

`add` also supports options to only add files whose paths match (or do not match) specified regular expressions: see the docstring for details. 

You can also specify a *metadata* map for all the files added in a particular call to `add`, using the `:meta` option. This is data that is attached to each file and can later be used to select or filter specific files. This is useful, among other things, for keeping track of a file's role in some process, and whether it should be included in a final build or not.

```clojure
(def my-fileset (fs/add (fs/fileset) (io/file "some/directory")
                        :include [#".*\.clj$"]
                        :meta {:role :source-code}))
```

Note that metadata is not the same as Clojure metadata; it does affect the equality semantics of the fileset.

#### Reading files in a fileset

You can list the files in a fileset using `ls`, which returns a collection of paths that are present in the fileset.

For each path, you can use any of the following functions:

- `hash` - get the MD5 hash of a file
- `timestamp` - get the "last modified" time of a file
- `content` - open a `java.io.InputStream` on the content of the file.

#### Committing

Interacting with a fileset programatically through the Clojure API is somewhat cumbersome. To perform arbitrary operations on a fileset, it is often necessary to *commit* the fileset, dumping its contents to a concrete location on the filesystem where they can be manipulated as normal files. 

To do this, use the `commit!` function, which takes a fileset and a directory (as a `java.io.File`) and writes all the files in the fileset to the directory.

Committing is efficient and does not perform a full copy; the emitted files are hard links to the underlying content. This also means that they are read-only; if you wish to modify a file, you must first copy it and re-add it using `add`.

```clojure
(require '[arachne.fileset :as fs])

(def output-dir (fs/tmpdir!))

(fs/commit! fs output-dir)
```

#### Removing files from a fileset

There are several ways to create a new fileset that does not contain certain files:

- `remove` removes the files at specific paths from a fileset.
- `filter` applies a predicate to each file in the fileset (using the internal tempfile instance) and returns a fileset containing only files for which the predicate returns true.
- `filter-meta` applies a predicate to the *metadata* of each file in the fileset and returns a fileset containing only files for which the predicate returns true.
- `empty` removes *all* the files from a fileset. This is different from simply creating a new fileset because it preserves the cache directory of the original (see *caching* below.)

#### Caching

Files may be cached to avoid unnecessary transformations or file manipulation. Caches can be persistent, even between processes. To create a fileset backed by a persistent cache, pass a directory to use as the cache (as a `java.io.File`) as an argument to `fileset` when creating a fileset.

Then, to use the cache, use `add-cached` instead of `add`. Instead of taking a directory of files to add, `add-cached` takes a cache key and a cache function. If the key is found in the cache, the cached files are simply added to the fileset. If the key is *not* found, the cache function is invoked and passed a temporary directory (as a `java.io.File`.) The cache function is expected to populate the temporary directory; the resulting files are added to both the fileset and to the cache.

#### Temporary files

To use filesets effectively, it is often necessary to make extensive use of temporary directories, and the system creates several temporary directories automatically in the course of operations.

You can obtain a new, empty temporary directory by calling `tmpdir!`. Directories created by `tmpdir!` are created in a directory suitable for temporary files (as determined by the filesystem), and are also registered with the JVM for deletion via a shutdown hook. 

## How it Works

Under the hood, the system maintains a directory (the "blob store") full of content-addressed files; that is, files that are named according to the MD5 hash of their contents and their last-modified timestamp. Whenever a file is added to any fileset using `add`, it is added to the blob store. The blob is never modified or deleted until the JVM shuts down.

A fileset is essentialy just a map of user-level paths (e.g, `"foo/bar.clj"`) to the paths of the corresponding blobs in the blob store (e.g, `"0ac6536c01c4720c6eee617785027c66.1472514953000"`).

Wherever possible (e.g, when using `commit!`, or caching), hard links are used to avoid performing a full copy of a file's contents. This makes most operations (aside from the initial `add!`) fast and lightweight.

## Differences from Boot

- When you `commit!`, you choose a directory instead using an implicit one.
- Multiple fileset instances are fully supported without restrictions, including use in multithreaded environments.
- Filesets no longer have any concept of roles, and no "source", "resource", "input" or "output" status. This can be trivally implemented using metadata, if desired.
-  Filesets have been decoupled from the concept of a classpath; they now have nothing to do with eachother (unless you happen to `commit!` to a directory on the classpath.)
- The API has been rewoked such that users do not need to interact with the underlying ITmpFile instances. Several convenience functions have been added.

## License

Copyright © 2016 Luke VanderHart, Alan Dipert and Micha Niskin 

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
