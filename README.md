# Fetch

[![Join the chat at https://gitter.im/47deg/fetch](https://badges.gitter.im/47deg/fetch.svg)](https://gitter.im/47deg/fetch?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Build status](https://img.shields.io/travis/47deg/fetch.svg)](https://travis-ci.org/47deg/fetch)

A library for Simple & Efficient data access in Scala and Scala.js

- [Documentation](http://47deg.github.io/fetch/docs)

## Installation

Add the following dependency to your project's build file.

For Scala 2.11.x:

```scala
"com.fortysevendeg" %% "fetch" %% "0.2.0"
```

Or, if using Scala.js (0.6.x):

```scala
"com.fortysevendeg" %%% "fetch" %% "0.2.0"
```




## Remote data

Fetch is a library for making access to data both simple & efficient. Fetch is especially useful when querying data that
has a latency cost, such as databases or web services.

## Define your data sources

To tell Fetch how to get the data you want, you must implement the `DataSource` typeclass. Data sources have `fetchOne` and `fetchMany` methods that define how to fetch such a piece of data.

Data Sources take two type parameters:

<ol>
<li><code>Identity</code> is a type that has enough information to fetch the data</li>
<li><code>Result</code> is the type of data we want to fetch</li>
</ol>

```scala
import cats.data.NonEmptyList

trait DataSource[Identity, Result]{
  def fetchOne(id: Identity): Query[Option[Result]]
  def fetchMany(ids: NonEmptyList[Identity]): Query[Map[Identity, Result]]
}
```

We'll implement a dummy data source that can convert integers to strings. For convenience, we define a `fetchString` function that lifts identities (`Int` in our dummy data source) to a `Fetch`. 

```scala
import cats.data.NonEmptyList
import cats.std.list._
import fetch._

implicit object ToStringSource extends DataSource[Int, String]{
  override def fetchOne(id: Int): Query[Option[String]] = {
    Query.later({
      println(s"[${Thread.currentThread.getId}] One ToString $id")
      Option(id.toString)
    })
  }
  override def fetchMany(ids: NonEmptyList[Int]): Query[Map[Int, String]] = {
    Query.later({
      println(s"[${Thread.currentThread.getId}] Many ToString $ids")
      ids.unwrap.map(i => (i, i.toString)).toMap
    })
  }
}

def fetchString(n: Int): Fetch[String] = Fetch(n) // or, more explicitly: Fetch(n)(ToStringSource)
```

## Creating and running a fetch

Now that we can convert `Int` values to `Fetch[String]`, let's try creating a fetch.

```scala
import fetch.syntax._

val fetchOne: Fetch[String] = fetchString(1)
```

We'll run our fetches to the ambiend `Id` monad in our examples, let's do some imports.

```scala
import cats.Id
import fetch.unsafe.implicits._
import fetch.syntax._
```

Note that in real-life scenarios you'll want to run a fetch to a concurrency monad, synchronous execution of a fetch
is only supported in Scala and not Scala.js and is meant for experimentation purposes.

Let's run it and wait for the fetch to complete:

```scala
fetchOne.runA[Id]
// [102] One ToString 1
// res3: cats.Id[String] = 1
```

## Batching

Multiple fetches to the same data source are automatically batched. For illustrating it, we are going to compose three independent fetch results as a tuple.

```scala
import cats.syntax.cartesian._
// import cats.syntax.cartesian._

val fetchThree: Fetch[(String, String, String)] = (fetchString(1) |@| fetchString(2) |@| fetchString(3)).tupled
// fetchThree: fetch.Fetch[(String, String, String)] = Gosub(Gosub(Suspend(Concurrent(List(FetchMany(OneAnd(1,List(2, 3)),ToStringSource$@6fb256ea)))),<function1>),<function1>)
```

When executing the above fetch, note how the three identities get batched and the data source is only queried once.

```scala
fetchThree.runA[Id]
// [102] Many ToString OneAnd(1,List(2, 3))
// res4: cats.Id[(String, String, String)] = (1,2,3)
```

## Parallelism

If we combine two independent fetches from different data sources, the fetches can be run in parallel. First, let's add a data source that fetches a string's size.

This time, instead of creating the results with `Query#later` we are going to do it with `Query#async` for emulating an asynchronous data source.

```scala
implicit object LengthSource extends DataSource[String, Int]{
  override def fetchOne(id: String): Query[Option[Int]] = {
    Query.async((ok, fail) => {
      println(s"[${Thread.currentThread.getId}] One Length $id")
      ok(Option(id.size))
    })
  }
  override def fetchMany(ids: NonEmptyList[String]): Query[Map[String, Int]] = {
    Query.async((ok, fail) => {
      println(s"[${Thread.currentThread.getId}] Many Length $ids")
      ok(ids.unwrap.map(i => (i, i.size)).toMap)
    })
  }
}

def fetchLength(s: String): Fetch[Int] = Fetch(s)
```

And now we can easily receive data from the two sources in a single fetch. 

```scala
val fetchMulti: Fetch[(String, Int)] = (fetchString(1) |@| fetchLength("one")).tupled
```

Note how the two independent data fetches are run in parallel, minimizing the latency cost of querying the two data sources.

```scala
fetchMulti.runA[Id]
// [102] One ToString 1
// [103] One Length one
// res6: cats.Id[(String, Int)] = (1,3)
```

## Caching

When fetching an identity, subsequent fetches for the same identity are cached. Let's try creating a fetch that asks for the same identity twice.

```scala
val fetchTwice: Fetch[(String, String)] = for {
  one <- fetchString(1)
  two <- fetchString(1)
} yield (one, two)
```

While running it, notice that the data source is only queried once. The next time the identity is requested it's served from the cache.

```scala
fetchTwice.runA[Id]
// [102] One ToString 1
// res7: cats.Id[(String, String)] = (1,1)
```
