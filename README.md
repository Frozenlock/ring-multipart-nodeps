# ring-multipart-nodeps

A zero-dependency replacement for `ring.middleware.multipart-params` with Babashka compatibility.

## Overview

`ring-multipart-nodeps` parses multipart/form-data requests in Clojure without external dependencies. It provides the same functionality as Ring's built-in `multipart-params` middleware but works in Babashka environments.

### Features

- Drop-in replacement for `ring.middleware.multipart-params`
- Zero external dependencies
- Works in both JVM Clojure and Babashka
- Proper handling of binary files (PNG, JPG, PDF, etc.)
- Supports custom file stores (in-memory or temp files)
- Progress tracking for file uploads

## Installation

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.frozenlock/ring-multipart-nodeps.svg)](https://clojars.org/org.clojars.frozenlock/ring-multipart-nodeps)


## Usage

The API mirrors Ring's standard multipart handling:

```clojure
(ns my-app.handler
  (:require [ring-multipart-nodeps.core :as mp]))

;; Basic usage
(def app
  (-> handler
      (mp/wrap-multipart-params)))

;; With custom options
(def app-with-options
  (-> handler
      (mp/wrap-multipart-params
        {:store (mp/temp-file-store)  ;; or mp/default-byte-array-store for in-memory
         :encoding "UTF-8"
         :fallback-encoding "UTF-8"
         :progress-fn (fn [request bytes-read content-length item-count]
                        (println "Processed" bytes-read "of" content-length "bytes"))})))
```

### Supported Options

| Option               | Description                                                                                                                             |
|----------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `:encoding`          | Forced character encoding for fields. Overrides part Content-Type.                                                                      |
| `:fallback-encoding` | Encoding used if part has no Content-Type charset. Defaults to request encoding or UTF-8.                                               |
| `:store`             | Function to handle file uploads. Takes map with `:filename`, `:content-type`, `:stream`, `:part-headers`. Default is `temp-file-store`. |
| `:progress-fn`       | Function called during uploads with parameters: request, bytes-read, content-length, item-count.                                        |

### Not Yet Supported

The following options from Ring's original middleware are not yet supported:

- `:max-file-size` - Limit on maximum file size in bytes
- `:max-file-count` - Limit on maximum number of files in a request
- `:error-handler` - Custom handler for when limits are exceeded
- Special handling of a part named `_charset_` for encoding detection


## License

Copyright © 2025 Frozenlock

Distributed under the Eclipse Public License version 2.0.
