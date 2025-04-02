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
         :progress-fn (fn [bytes is-file?]
                        (when is-file?
                          (println "Processed" bytes "bytes")))})))
```


## License

Copyright © 2025 Frozenlock

Distributed under the Eclipse Public License version 2.0.
