"""Order lookups."""


def find_order(db, order_id: int):
    return db.execute("SELECT id, total FROM orders WHERE id = %s", (order_id,)).fetchone()
