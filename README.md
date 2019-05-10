# Vase // Elasticsearch

[![Coverage Status](https://coveralls.io/repos/github/nytimes/vase.elasticsearch/badge.svg?branch=master&t=aFiCSQ)](https://coveralls.io/github/nytimes/vase.elasticsearch?branch=master)

Elasticsearch extensions for [Vase][vase] and [Fern][fern].

This library adds a few literals which make it possible to quickly
create APIs using Elasticsearch. This uses the Elasticsearch library
[Spandex][spandex] for the underlying Elasticsearch client.

## Usage
### vase.elasticsearch/connect

This literal will connect to an Elasticsearch and return an
interceptor which injects the connection in the context on every
request.

The only required argument is `:hosts` which accepts a collection of
URIs of nodes. Additional allowed arguments specified [here][spandex-client]

#### Example

```clojure
(fern/lit vase.elasticsearch/connect
          {:hosts ["http://localhost:9200"]})
```

### vase.elasticsearch/create

Create an Elasticsearch index using the provided name and settings. If
an index with the same name exists, this is a no-op.

| Param       | Meaning                                                            |
|:------------|:-------------------------------------------------------------------|
| `:name`     | Optional. The name of the interceptor.                             |
| `:index`    | The name of the index.                                             |
| `:settings` | The index settings specified inline, in a JSON, or in a YAML file. |

#### Example

This creates an index named `tmdb` using the Elasticsearch
configuration specified in the file `settings.json`.

```clojure
(fern/lit vase.elasticsearch/create
          {:name     :tmdb/create
           :index    "tmdb"
           :settings "settings.json"})
```

### vase.elasticsearch/get-document

Get a document from the index by its id.

| Param      | Meaning                                                            |
|:-----------|:-------------------------------------------------------------------|
| `:name`    | Optional. The name of the interceptor.                             |
| `:index`   | The name of the index.                                             |
| `:type`    | Optional. The document type. Defaults to `:_doc`                   |
| `:id-path` | The path within the [context map][context-map] to the document id. |

#### Example

This builds an interceptor which will lool up a document of type
`:_doc` in the index `my-index`. The id is sourced from the path
paramter `:id`.

``` clojure
(fern/lit vase.elasticsearch/get-document
                           {:name    :tmdb/get-document
                            :index   "my-index"
                            :type    :_doc
                            :id-path [:request :path-params :id]})
```

### vase.elasticsearch/put-document

Put a document into the index.

| Param        | Meaning                                                                                                                |
|:-------------|:-----------------------------------------------------------------------------------------------------------------------|
| `:name`      | Optional. The name of the interceptor.                                                                                 |
| `:index`     | The name of the index.                                                                                                 |
| `:type`      | Optional. The document type. Defaults to `:_doc`.                                                                                          |
| `:id-path`   | The path within the [context map][context-map] to the document id.                                                     |
| `:body-path` | The path within the [context map][context-map] to the document body. This is what will get indexed into Elasticsearch. |

#### Example

``` clojure
(fern/lit vase.elasticsearch/put-document
                           {:name      :tmdb/put-document
                            :index     "my-index"
                            :type      :_doc
                            :id-path   [:request :path-params :id]
                            :body-path [:request :body]})
```

### vase.elasticsearch/delete-document

Delete a document from the index by its id.

| Param      | Meaning                                                            |
|:-----------|:-------------------------------------------------------------------|
| `:name`    | Optional. The name of the interceptor.                             |
| `:index`   | The name of the index.                                             |
| `:type`    | Optional. The document type.                                       |
| `:id-path` | The path within the [context map][context-map] to the document id. |

#### Example

``` clojure
(fern/lit vase.elasticsearch/delete-document
                           {:name    :tmdb/delete-document
                            :index   "my-index"
                            :type    :_doc
                            :id-path [:request :path-params :id]})
```

### vase.elasticsearch/search

Perform a search against an Elasticsearch index (or indices if a
vector of index names). See the [Elasticsearch docs][search-docs] for
more details.

| Param     | Meaning                                                                                                                                             |
|:----------|:----------------------------------------------------------------------------------------------------------------------------------------------------|
| `:name`   | Optional. The name of the interceptor.                                                                                                              |
| `:index`  | The name of the index.                                                                                                                              |
| `:method` | Optional. The HTTP method to use when querying Elasticsearch. Defaults to `:get`.                                                                   |
| `:params` | Variables to bind from the request map. These values come from query and path parameters.                                                           |
| `:body`   | The query to send to Elasticsearch. You may use programming constructs within the body. See the [Elasticsearch docs][search-docs] for more details. |

#### Example

```clojure
(fern/lit vase.elasticsearch/search
          {:name   :tmdb/count
           :index  "my-index"
           ;; The variable `q` comes a query paramter. So, e.g., if we
           ;; have some endpoint that's queryied like so
           ;; `/search?q=nytimes`, `q` would be bound to `nytimes`.
           :params [q]
           ;; Notice that we are using a programming construct within
           ;; an otherwise declarative query. This opens up a wide
           ;; possibility of behavior within a compact form.
           :body   {:query
                    (if q
                      {:multi_match {:query  q
                                     :fields ["overview"
                                              "title"
                                              "directors.name"
                                              "cast.name"]}}
                      {:match_all {}})}})
```


### vase.elasticsearch/count

Return a count for the number of matches for a given query. See the
[Elasticsearch docs][count-docs] for more details.

| Param     | Meaning                                                                                                                                                  |
|:----------|:---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `:name`   | Optional. The name of the interceptor.                                                                                                                   |
| `:index`  | The name of the index.                                                                                                                                   |
| `:method` | Optional. The HTTP method to use when querying Elasticsearch. Defaults to `:get`.                                                                        |
| `:params` | Variables to bind from the request map. These values come from query and path parameters.                                                                |
| `:body`   | The count query to send to Elasticsearch. You may use programming constructs within the body. See the [Elasticsearch docs][count-docs] for more details. |

### vase.elasticsearch/request

Perform an abritrary HTTP request against Elasticsearch. You can use
this to peform any request not covered by one of the named actions above.

| Param     | Meaning                                                                                   |
|:----------|:------------------------------------------------------------------------------------------|
| `:name`   | Optional. The name of the interceptor.                                                    |
| `:index`  | The name of the index.                                                                    |
| `:method` | Optional. The HTTP method to use when querying Elasticsearch. Defaults to `:get`.         |
| `:params` | Variables to bind from the request map. These values come from query and path parameters. |
| `:body`   | The query to send to Elasticsearch. You may use programming constructs within the body.   |

#### Constructing a query

```clojure
(fern/lit vase.elasticsearch/request
          {:params [q dbg]
           :url    [:tmdb :_search]
           :method :get
           :body   {:_source [:title :release_year]
                    :explain (or dbg false)
                    :query   (let [q (or q "")]
                               {:multi_match
                                {:query  q
                                 :fields ["title^0.1", "overview"]}})}})

```

#### Indexing content

```clojure
(fern/lit vase.elasticsearch/request
          {:params [id]
           :url    [:tmdb :_doc id]
           :method :put})

```

## Running the Example

Start an Elasticsearch cluster with Docker compose:

```sh
docker-compose up
```

In a new terminal, start the example API:

```sh
clj -A:test:service
```

In a new terminal, index content:

```sh
clj -A:test:index
```

You can now start issuing queries against the API:

```sh
curl -s 'localhost:8080/search?q=basketball+with+cartoon+aliens' | jq .hits.hits[]._source.title
"Space Jam"
"Just Wright"
"Who Wants To Kill Jessie?"
"He Got Game"
"Grown Ups"
"Air Bud"
"Glory Road"
"Semi-Pro"
"Coach Carter"
"Speed Racer"
```

The entire API is define using the configuration file in
`dev/tmdb.fern`. You can easily iterate on this query to make a search
that works for you.

Relasing
--------

Example:

``` sh
$ make release/patch clean jar deploy
```

[vase]: https://github.com/cognitect-labs/vase
[fern]: https://github.com/cognitect-labs/fern
[spandex]: https://github.com/mpenet/spandex
[spandex-client]: https://mpenet.github.io/spandex/qbits.spandex.html#var-client
[context-map]: http://pedestal.io/reference/context-map
[search-docs]: https://www.elastic.co/guide/en/elasticsearch/reference/current/search-search.html
[count-docs]: https://www.elastic.co/guide/en/elasticsearch/reference/current/search-count.html
