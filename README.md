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

To tell Fetch how to get the data you want, you must implement the `DataSource` typeclass. Data sources have a `fetch` method that
defines how to fetch such a piece of data.

Data Sources take two type parameters:

<ol>
<li><code>Identity</code> is a type that has enough information to fetch the data</li>
<li><code>Result</code> is the type of data we want to fetch</li>
</ol>

```scala
import monix.eval.Task
import cats.data.NonEmptyList

trait DataSource[Identity, Result]{
  def fetchOne(id: Identity): Task[Option[Result]]
  def fetchMany(ids: NonEmptyList[Identity]): Task[Map[Identity, Result]]
}
```

We'll implement a dummy data source that can convert integers to strings. For convenience, we define a `fetchString` function that lifts identities (`Int` in our dummy data source) to a `Fetch`. 

```scala
import monix.eval.Task
import cats.data.NonEmptyList
import cats.std.list._
import fetch._

implicit object ToStringSource extends DataSource[Int, String]{
  override def fetchOne(id: Int): Task[Option[String]] = {
    Task.now({
      println(s"[${Thread.currentThread.getId}] One ToString $id")
      Option(id.toString)
    })
  }
  override def fetchMany(ids: NonEmptyList[Int]): Task[Map[Int, String]] = {
    Task.now({
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

Now that we have created a fetch, we can run it to a `Task`. Note that when we create a task we are not computing any value yet. Having a `Task` instance allows us to try to run it synchronously or asynchronously, choosing a scheduler.

```scala
val result: Task[String] = fetchOne.runA
// result: monix.eval.Task[String] = BindSuspend(<function0>,<function1>)
```

We can try to run `result` synchronously with `Task#coeval`. 

```scala
import monix.execution.Scheduler.Implicits.global
// import monix.execution.Scheduler.Implicits.global

result.coeval.value
// [1026] One ToString 1
// res3: Either[monix.execution.CancelableFuture[String],String] = Right(1)
```

Since we calculated the results eagerly using `Task#now`, we can run this fetch synchronously.

As you can see in the previous example, the `ToStringSource` is queried once to get the value of 1.

## Batching

Multiple fetches to the same data source are automatically batched. For illustrating it, we are going to compose three independent fetch results as a tuple.

```scala
import cats.syntax.cartesian._
// import cats.syntax.cartesian._

val fetchThree: Fetch[(String, String, String)] = (fetchString(1) |@| fetchString(2) |@| fetchString(3)).tupled
// fetchThree: fetch.Fetch[(String, String, String)] = Gosub(Gosub(Suspend(Concurrent(List(FetchMany(OneAnd(1,List(2, 3)),ToStringSource$@77b748dd)))),<function1>),<function1>)

val result: Task[(String, String, String)] = fetchThree.runA
// result: monix.eval.Task[(String, String, String)] = BindSuspend(<function0>,<function1>)
```




When executing the above fetch, note how the three identities get batched and the data source is only queried once. Let's pretend we have a function from `Task[A]` to `A` called `await`.

```scala
await(result)
// [1026] Many ToString OneAnd(1,List(2, 3))
// res4: (String, String, String) = (1,2,3)
```

## Parallelism

If we combine two independent fetches from different data sources, the fetches can be run in parallel. First, let's add a data source that fetches a string's size.

This time, instead of creating the results with `Task#now` we are going to do it with `Task#apply` for emulating an asynchronous data source.

```scala
implicit object LengthSource extends DataSource[String, Int]{
  override def fetchOne(id: String): Task[Option[Int]] = {
    Task({
      println(s"[${Thread.currentThread.getId}] One Length $id")
      Option(id.size)
    })
  }
  override def fetchMany(ids: NonEmptyList[String]): Task[Map[String, Int]] = {
    Task({
      println(s"[${Thread.currentThread.getId}] Many Length $ids")
      ids.unwrap.map(i => (i, i.size)).toMap
    })
  }
}

def fetchLength(s: String): Fetch[Int] = Fetch(s)
```

And now we can easily receive data from the two sources in a single fetch. 

```scala
val fetchMulti: Fetch[(String, Int)] = (fetchString(1) |@| fetchLength("one")).tupled
// fetchMulti: fetch.Fetch[(String, Int)] = Gosub(Gosub(Suspend(Concurrent(List(FetchMany(OneAnd(1,List()),ToStringSource$@77b748dd), FetchMany(OneAnd(one,List()),LengthSource$@741119c5)))),<function1>),<function1>)

val result = fetchMulti.runA
// result: monix.eval.Task[(String, Int)] = BindSuspend(<function0>,<function1>)
```

Note how the two independent data fetches are run in parallel, minimizing the latency cost of querying the two data sources.

```scala
await(result)
// [1026] Many ToString OneAnd(1,List())
// [1027] Many Length OneAnd(one,List())
// res6: (String, Int) = (1,3)
```

## Caching

When fetching an identity, subsequent fetches for the same identity are cached. Let's try creating a fetch that asks for the same identity twice.

```scala
val fetchTwice: Fetch[(String, String)] = for {
  one <- fetchString(1)
  two <- fetchString(1)
} yield (one, two)
// fetchTwice: fetch.Fetch[(String, String)] = Gosub(Suspend(FetchOne(1,ToStringSource$@77b748dd)),<function1>)
```

While running it, notice that the data source is only queried once. The next time the identity is requested it's served from the cache.

```scala
val result: (String, String) = await(fetchTwice.runA)
// [1026] One ToString 1
// result: (String, String) = (1,1)
```
