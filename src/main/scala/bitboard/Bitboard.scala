package chess
package bitboard

import scala.collection.mutable.ListBuffer

opaque type Bitboard = Long
object Bitboard extends TotalWrapper[Bitboard, Long]:

  val ALL: Bitboard     = Bitboard(-1L)
  val empty: Bitboard   = Bitboard(0L)
  val corners: Bitboard = Bitboard(0x8100000000000081L)

  private val RANKS = Array.fill(8)(0L)
  private val FILES = Array.fill(8)(0L)

  private[bitboard] val KNIGHT_DELTAS     = Array[Int](17, 15, 10, 6, -17, -15, -10, -6)
  private[bitboard] val BISHOP_DELTAS     = Array[Int](7, -7, 9, -9)
  private[bitboard] val ROOK_DELTAS       = Array[Int](1, -1, 8, -8)
  private[bitboard] val KING_DELTAS       = Array[Int](1, 7, 8, 9, -1, -7, -8, -9)
  private[bitboard] val WHITE_PAWN_DELTAS = Array[Int](7, 9)
  private[bitboard] val BLACK_PAWN_DELTAS = Array[Int](-7, -9)

  private[bitboard] val KNIGHT_ATTACKS     = Array.fill(64)(0L)
  private[bitboard] val KING_ATTACKS       = Array.fill(64)(0L)
  private[bitboard] val WHITE_PAWN_ATTACKS = Array.fill(64)(0L)
  private[bitboard] val BLACK_PAWN_ATTACKS = Array.fill(64)(0L)

  private[bitboard] val BETWEEN = Array.ofDim[Long](64, 64)
  private[bitboard] val RAYS    = Array.ofDim[Long](64, 64)

  // Large overlapping attack table indexed using magic multiplication.
  private[bitboard] val ATTACKS = Array.fill(88772)(0L)

  inline def rank(inline r: Rank): Bitboard                  = RANKS(r.value)
  inline def file(inline f: File): Bitboard                  = FILES(f.value)
  inline def ray(inline from: Pos, inline to: Pos): Bitboard = RAYS(from.value)(to.value)

  /** Slow attack set generation. Used only to bootstrap the attack tables.
    */
  private[bitboard] def slidingAttacks(square: Int, occupied: Bitboard, deltas: Array[Int]): Bitboard =
    var attacks = 0L
    deltas.foreach { delta =>
      var sq: Int = square
      var i       = 0
      while
        i += 1
        sq += delta
        val con = (sq < 0 || 64 <= sq || distance(sq, sq - delta) > 2)
        if (!con)
          attacks |= 1L << sq

        !(occupied.contains(Pos(sq)) || con)
      do ()
    }
    attacks

  private def initMagics(square: Int, magic: Magic, shift: Int, deltas: Array[Int]) =
    var subset = 0L
    while
      val attack = slidingAttacks(square, subset, deltas)
      val idx    = ((magic.factor * subset) >>> (64 - shift)).toInt + magic.offset
      ATTACKS(idx) = attack

      // Carry-rippler trick for enumerating subsets.
      subset = (subset - magic.mask) & magic.mask

      subset != 0
    do ()

  private def initialize() =
    (0 until 8).foreach { i =>
      RANKS(i) = 0xffL << (i * 8)
      FILES(i) = 0x0101010101010101L << i
    }

    val squareRange = 0 until 64
    squareRange.foreach { sq =>
      // println(s"$sq")
      KNIGHT_ATTACKS(sq) = slidingAttacks(sq, Bitboard.ALL, KNIGHT_DELTAS)
      KING_ATTACKS(sq) = slidingAttacks(sq, Bitboard.ALL, KING_DELTAS)
      WHITE_PAWN_ATTACKS(sq) = slidingAttacks(sq, Bitboard.ALL, WHITE_PAWN_DELTAS)
      BLACK_PAWN_ATTACKS(sq) = slidingAttacks(sq, Bitboard.ALL, BLACK_PAWN_DELTAS)

      initMagics(sq, Magic.ROOK(sq), 12, ROOK_DELTAS)
      initMagics(sq, Magic.BISHOP(sq), 9, BISHOP_DELTAS)
    }

    for
      a <- squareRange
      b <- squareRange
      _ =
        if slidingAttacks(a, 0, ROOK_DELTAS).contains(Pos(b)) then
          BETWEEN(a)(b) = slidingAttacks(a, 1L << b, ROOK_DELTAS) & slidingAttacks(b, 1L << a, ROOK_DELTAS)
          RAYS(a)(b) =
            (1L << a) | (1L << b) | slidingAttacks(a, 0, ROOK_DELTAS) & slidingAttacks(b, 0, ROOK_DELTAS)
        else if slidingAttacks(a, 0, BISHOP_DELTAS).contains(Pos(b)) then
          BETWEEN(a)(b) =
            slidingAttacks(a, 1L << b, BISHOP_DELTAS) & slidingAttacks(b, 1L << a, BISHOP_DELTAS)
          RAYS(a)(b) =
            (1L << a) | (1L << b) | slidingAttacks(a, 0, BISHOP_DELTAS) & slidingAttacks(b, 0, BISHOP_DELTAS)
    yield ()

  initialize()

  def aligned(a: Pos, b: Pos, c: Pos): Boolean =
    ray(a, b).contains(c)

  def between(a: Pos, b: Pos): Bitboard =
    BETWEEN(a.value)(b.value)

  extension (s: Pos)
    def bishopAttacks(occupied: Bitboard): Bitboard =
      val magic = Magic.BISHOP(s.value)
      ATTACKS(((magic.factor * (occupied & magic.mask) >>> (64 - 9)).toInt + magic.offset))

    def rookAttacks(occupied: Bitboard): Bitboard =
      val magic = Magic.ROOK(s.value)
      ATTACKS(((magic.factor * (occupied & magic.mask) >>> (64 - 12)).toInt + magic.offset))

    def queenAttacks(occupied: Bitboard): Bitboard =
      bishopAttacks(occupied) ^ rookAttacks(occupied)

    def pawnAttacks(color: Color): Bitboard =
      color match
        case Color.White => WHITE_PAWN_ATTACKS(s.value)
        case Color.Black => BLACK_PAWN_ATTACKS(s.value)

    def kingAttacks: Bitboard =
      KING_ATTACKS(s.value)

    def knightAttacks: Bitboard =
      KNIGHT_ATTACKS(s.value)

    def bitboard: Bitboard =
      1L << s.value

  type LongRuntime[A] = SameRuntime[A, Long]
  extension (a: Bitboard)
    inline def unary_- : Bitboard                                            = -a
    inline def unary_~ : Bitboard                                            = ~a
    inline infix def >(inline o: Bitboard): Boolean                          = a > o
    inline infix def <(inline o: Bitboard): Boolean                          = a < o
    inline infix def >=(inline o: Bitboard): Boolean                         = a >= o
    inline infix def <=(inline o: Bitboard): Boolean                         = a <= o
    inline infix def +(inline o: Bitboard): Bitboard                         = a + o
    inline infix def -(inline o: Bitboard): Bitboard                         = a - o
    inline infix def &(inline o: Bitboard): Bitboard                         = a & o
    inline infix def ^(inline o: Bitboard): Bitboard                         = a ^ o
    inline infix def |(inline o: Bitboard): Bitboard                         = a | o
    inline infix def <<(inline o: Bitboard): Bitboard                        = a << o
    inline infix def >>>(inline o: Bitboard): Bitboard                       = a >>> o
    inline infix def >[B](inline o: B)(using sr: LongRuntime[B]): Boolean    = >(sr(o))
    inline infix def <[B](inline o: B)(using sr: LongRuntime[B]): Boolean    = <(sr(o))
    inline infix def >=[B](inline o: B)(using sr: LongRuntime[B]): Boolean   = >=(sr(o))
    inline infix def <=[B](inline o: B)(using sr: LongRuntime[B]): Boolean   = <=(sr(o))
    inline infix def +[B](inline o: B)(using sr: LongRuntime[B]): Bitboard   = a + sr(o)
    inline infix def -[B](inline o: B)(using sr: LongRuntime[B]): Bitboard   = a - sr(o)
    inline infix def &[B](inline o: B)(using sr: LongRuntime[B]): Bitboard   = a & sr(o)
    inline infix def ^[B](inline o: B)(using sr: LongRuntime[B]): Bitboard   = a ^ sr(o)
    inline infix def |[B](inline o: B)(using sr: LongRuntime[B]): Bitboard   = a | sr(o)
    inline infix def <<[B](inline o: B)(using sr: LongRuntime[B]): Bitboard  = a << sr(o)
    inline infix def >>>[B](inline o: B)(using sr: LongRuntime[B]): Bitboard = a >>> sr(o)

    def contains(pos: Pos): Boolean =
      (a & (1L << pos.value)).nonEmpty

    def moreThanOne: Boolean =
      (a & (a - 1L)).nonEmpty

    def lsb: Option[Pos] = Pos.at(java.lang.Long.numberOfTrailingZeros(a))

    def occupiedSquares: List[Pos] =
      fold(List[Pos]())((xs, pos) => xs :+ pos)

    // total not empty position
    def count: Int =
      fold(0)((count, _) => count + 1)

    def fold[A](init: A)(f: (A, Pos) => A): A =
      var bb     = a
      var result = init
      while bb != 0
      do
        result = f(result, bb.lsb.get)
        bb &= (bb - 1L)
      result

    inline def isEmpty: Boolean  = a == empty
    inline def nonEmpty: Boolean = !isEmpty

  // TODO move to color
  extension (c: Color)

    def secondRank: Rank =
      c match
        case Color.White => Rank.Second
        case Color.Black => Rank.Seventh

    def seventhRank: Rank =
      c match
        case Color.White => Rank.Seventh
        case Color.Black => Rank.Second

  private def distance(a: Int, b: Int): Int =
    Math.max(Math.abs(a.file - b.file), Math.abs(a.rank - b.rank))

  extension (a: Int)
    private def file = a & 7
    private def rank = a >>> 3

  extension (a: Long) def bb = Bitboard(a)
