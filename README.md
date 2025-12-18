# sexpmachine

Find repeating s-expression patterns in Clojure codebases.

Analyzes `.clj`, `.cljc`, and `.cljs` files to identify duplicated code patterns that might benefit from refactoring into shared functions or macros.

## Installation

Requires [Babashka](https://babashka.org/) and [bbin](https://github.com/babashka/bbin).

```bash
bbin install io.github.Ralii/sexpmachine
```

## Usage

```
sexpmachine <directory> [min-size] [min-frequency] [options]

Arguments:
  directory      Directory to analyze (required)
  min-size       Minimum expression size in nodes (default: 3)
  min-frequency  Minimum occurrences to report (default: 5)

Options:
  --no-calls           Exclude function/macro calls from results
  --no-keyword-chains  Exclude keyword-heavy expressions (maps, get-in paths, etc.)
  --help               Show this help message
```

## Examples

Analyze a project with default settings:

```bash
sexpmachine src
```

Find patterns with at least 4 nodes that appear 3+ times:

```bash
sexpmachine src 4 3
```

Exclude function calls to focus on data patterns:

```bash
sexpmachine src 3 2 --no-calls
```

Exclude keyword-heavy expressions (get-in paths, keyword maps, etc.):

```bash
sexpmachine src 3 2 --no-keyword-chains
```

## Example Output

```
Analyzing . (min size: 4 , min frequency: 3 , excluding calls)
---

Pattern: {:color :blue}
  Size: 4 nodes
  Found 8 times:
    - src/components/button.clj :35
    - src/components/button.clj :36
    - src/components/card.clj :55
    - src/views/dashboard.clj :7
    - src/views/dashboard.clj :11
    - src/views/settings.clj :7
    - src/views/settings.clj :8
    - src/views/settings.clj :12

Pattern: {:class "container"}
  Size: 4 nodes
  Found 7 times:
    - src/components/layout.clj :34
    - src/components/layout.clj :53
    - src/views/home.clj :6
    - src/views/home.clj :10
    - src/views/about.clj :6
    - src/views/about.clj :11
    - src/views/about.clj :15
```

## How It Works

sexpmachine uses [rewrite-clj](https://github.com/clj-commons/rewrite-clj) to parse Clojure source files into ASTs. It then:

1. Recursively collects all sub-expressions from each file
2. Calculates expression size (number of nodes)
3. Groups identical expressions across the codebase
4. Reports patterns that meet the minimum size and frequency thresholds

The "size" metric counts all nodes in an expression, including nested structures. For example:
- `x` = 1 node
- `[a b]` = 3 nodes (vector + 2 symbols)
- `{:a 1}` = 4 nodes (map + map-entry + key + value)

## License

MIT
