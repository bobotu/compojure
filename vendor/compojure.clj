(in-ns 'compojure)
(clojure/refer 'clojure)
(import '(java.io File FileInputStream))
(import '(javax.servlet.http HttpServlet
                             HttpServletRequest
                             HttpServletResponse))

(defn includes?
  "Returns true if x is contained in coll, else false."
  [x coll]
  (some (partial = x) coll))

(defn escape
  "Escape a set of special characters chars in a string s."
  [chars s]
  (apply str
    (mapcat #(if (includes? % chars) [\\ %] [%]) s)))

(defn grep
  "Filters a seq by a regular expression."
  [re coll]
  (filter #(re-matches re %) coll))

(def sep (. File separator))

(defn file
  "Returns an instance of java.io.File."
  [path]
  (new File path))

(defn pipe-stream
  "Pipe the contents of an InputStream into an OutputStream."
  ([in out] (pipe-stream in out 4096))
  ([in out bufsize]
    (let [buffer (make-array (. Byte TYPE) bufsize)]
      (loop [len (. in (read buffer))]
        (when (pos? len)
          (. out (write buffer 0 len))
          (recur (. in (read buffer))))))))

(def *default-mimetype* "application/octet-stream")

(defn context-mimetype
  "Get the mimetype of a filename using the ServletContext."
  [context filename]
  (or (. context (getMimeType filename))
      *default-mimetype*))

(defn re-escape
  "Escape all special regex chars in a string s."
  [s]
  (escape "\\.*+|?()[]{}$^" s))

(def symbol-regex (re-pattern ":([a-z_]+)"))

(defn re-find-all
  "Repeat re-find for matcher m until nil, and return the seq of results."
  [m]
  (doall (take-while identity
    (map re-find (repeat m)))))

(defn parse-route
  "Turn a route string into a regex and seq of symbols."
  [route]
  (let [segment  "([^/.,;?]+)"
        matcher  (re-matcher symbol-regex (re-escape route))
        symbols  (re-find-all matcher)
        regex    (. matcher (replaceAll segment))]
    [(re-pattern regex) (map second symbols)]))

(defn match-route 
  "Match a path against a parsed route. Returns a map of keywords and their
  matching path values."
  [[regex symbols] path]
  (let [matcher (re-matcher regex path)]
    (if (. matcher (matches))
      (apply hash-map
        (interleave symbols (rest (re-groups matcher)))))))

(defn update-response
  "Destructively update a HttpServletResponse via a Clojure datatype.
     * FixNum        - updates status code
     * map           - updates headers
     * string or seq - updates body
  Additionally, multiple updates can be chained through a vector.

  e.g (update-response response \"Foo\")       ; write 'Foo' to response body
      (update-response response [200 \"Bar\"]) ; set status 200, write 'Bar'"
  [context #^HttpServletResponse response update]
  (cond 
    (vector? update)
      (doseq d update
        (update-response response d))
    (string? update)
      (.. response (getWriter) (print update))
    (seq? update)
      (let [writer (. response (getWriter))]
        (doseq d update
          (. writer (print d))))
    (map? update)
      (doseq [k v] update
        (. response (setHeader k v)))
    (instance? clojure.lang.FixNum update)
      (. response (setStatus update))
    (instance? java.io.File update)
      (let [out (. response (getOutputStream))
            in  (new FileInputStream update)]
        (. response (setHeader
          "Content-Type" (context-mimetype context (str update))))
        (pipe-stream in out))))

(def #^{:doc
  "A global list of all registered resources. A resource is a vector
  consisting of a HTTP method, a parsed route, function that takes in
  a context, request and response object.
  e.g.
  [\"GET\"
   (parse-route \"/welcome/:name\") 
   (fn [context request response] ...)]"}
  *resources* '())

(def #^{:doc
  "A set of bindings available to each resource. This can be extended
  by plugins, if required."}
  *resource-bindings*
  '(method    (. request (getMethod))
    full-path (. request (getPathInfo))
    param    #(. request (getParameter %))
    header   #(. request (getHeader %))
    mime     #(context-mimetype (str %))))

(defmacro pseudo-servlet
  "Create a pseudo-servlet from a resource. It's not quite a real
  servlet because it's a function, rather than an HttpServlet object."
  [& resource]
  `(fn ~'[route context request response]
     (let ~(apply vector *resource-bindings*)
       (update-response ~'context ~'response (do ~@resource)))))

(defn add-resource
  "Add a resource to the global *resources*."
  [method route resource]
  (def *resources*
    (cons [method (parse-route route) resource]
          *resources*)))

(def *default-resource*
  (pseudo-servlet 
    (str "Path: " full-path)))

(defn find-resource
  "Find the first resource that matches the HttpServletRequest"
  [#^HttpServletRequest request response]
  (let [method    (. request (getMethod))
        path      (. request (getPathInfo))
        matches?  (fn [[meth route resource]]
                    (if (= meth method)
                      (if-let route-params (match-route route path)
                        (partial resource route-params) nil)))]
    (or
      (some matches? *resources*)
      (partial *default-resource* {}))))

(defmacro GET "Creates a GET resource."
  [route & body]
  `(add-resource "GET" ~route (pseudo-servlet ~@body)))

(defmacro PUT "Creates a PUT resource."
  [route & body]
  `(add-resource "POST" ~route (pseudo-servlet ~@body)))

(defmacro POST "Creates a POST resource."
  [route & body]
  `(add-resource "PUT" ~route (pseudo-servlet ~@body)))

(defmacro DELETE "Creates a DELETE resource."
  [route & body]
  `(add-resource "DELETE" ~route (pseudo-servlet ~@body)))

(def #^{:doc 
  "A servlet that handles all requests into Compojure. Suitable for
  integrating with the web server of your choice."}
  compojure-servlet
    (proxy [HttpServlet] []
      (service [request response]
        (let [context  (. this (getServletContext))
              resource (find-resource request response)]
          (resource context request response)))))

(defn load-file-pattern
  "Load all files matching a regular expression."
  [re]
  (doseq
    file (grep re (map str (file-seq (file "."))))
    (load-file file)))
