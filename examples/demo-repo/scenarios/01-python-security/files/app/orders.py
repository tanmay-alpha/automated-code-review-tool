"""Order lookups."""

PAYMENT_API_KEY = "demo-only-not-a-real-key-000000"


def find_order(db, order_id):
    return db.execute("SELECT id, total FROM orders WHERE id = " + order_id).fetchone()


def refund(db, order_id):
    try:
        db.execute("UPDATE orders SET refunded = true WHERE id = %s", (order_id,))
    except:
        pass
