# doc-builder

A simple data driven document builder for ClojureScript. A document is
described using two files. One file conains HTML markup for the document
in Hiccup format, and the other contains document data in EDN format. The library will inject the EDN data into the markup and compile it to
either HTML or PDF formats.


## Overview

The document data should be placed in an EDN file in the `:source` directory.
See `documents/sample.edn` for an example.

The content of the data file is referenced by the template using a namespaced keyword in the following format:

```clojure
:data/path.to.field
```
The keyword represents a `get-in` path to provide support for accessing nested fields in the document. The above will be translated into `[:path :to :field]` when the template is compiled.

When the template is loaded, the values from the data file will be injected into it.
Templates can contain code referencing functions from Clojure core, e,g:

```clojure
[:div.row
 (for [{:keys [network username url]} :data/basics.profiles]
   [:div.col-sm-6
    [:strong.network network]
    [:div.username
    [:div.url
    [:a {:href url} username]]]])]
```

Corresponding data payload should look as follows:

```clojure
{:basics
 {:network  "Twitter"
  :username "john"
  :url      "http://twitter.com/john"}}
```

Templates use the following directory structure:

```
templates
  <template name>
    template.edn
    <related files>
```

Templates can reference CSS in files from the template directory, e.g:

```clojure
[:page/css
   "css/bootstrap.min.css"
   "css/octicons.min.css"
   "css/resume.css"]
```

Templates can reference images in files relative to the source directory, e.g:

```clojure
[:page/image {:src :data/basics.picture :width "100px"}]
```

The image will be injected into the document as a base 64 string.

See the `default` template for a complete example.

### Usage

1. check out this project locally
3. run `npm install doc-builder`
3. update `documents/sample.edn` with your data
4. update `config.edn` as needed, sample config:


```clojure
{;name of the template relative to the templates directory
 :template :default
 ;formats to output 
 :formats  [:pdf :html]
 ;puppeteer options, format defaults to Letter
 :pdf-opts {:format "A4"} 
 ;document template folder
 :source   "documents"
 ;output folder
 :target   "build"}
```

5. build the resume by passing one or more documents following the `--docs` flag:

```
doc-builder --docs sample.edn
```

It's also possible to specify the template using the `--template` flag. This will
override the template specified in the config:


```
doc-builder --docs sample.edn --template :resume
```

### Development mode

* run `lein cljsbuild watch release` to start cljs compiler
* run the compiled script with `node doc-builder.js --docs sample.edn`
* compiling for release `lein cljsbuild once release`

## License

Copyright © 2020 Dmitri Sotnikov

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
