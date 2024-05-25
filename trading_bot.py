import time
import json
from asset_calculator import AssetCalculator

class TradingBot:
    def __init__(self, calculator, config):
        self.calculator = calculator
        self.config = config

    def run(self):
        while True:
            try:
                self.check_and_trade()
                time.sleep(self.config['check_interval'])
            except Exception as e:
                print(f"An error occurred: {e}")
                time.sleep(self.config['check_interval'])

    def check_and_trade(self):
        cash_balance = self.calculator.get_balance()
        total_asset = self.calculator.get_total_asset()
        cash_ratio = self.calculator.get_cash_ratio()

        print(f"Cash Balance: {cash_balance}")
        print(f"Total Asset: {total_asset}")
        print(f"Cash Ratio: {cash_ratio:.2f}%")

        coin = "KRW-BTC"
        coin_balance = self.calculator.get_coin_balance(coin)
        current_price = self.calculator.get_current_price(coin)
        avg_buy_price = self.calculator.get_avg_buy_price(coin)

        print(f"{coin} Balance: {coin_balance}")
        print(f"{coin} Current Price: {current_price}")
        print(f"{coin} Average Buy Price: {avg_buy_price}")

        # Functionality 1: Buy if cash ratio >= threshold and current price < avg buy price
        if cash_ratio >= self.config['cash_ratio_threshold'] and current_price < avg_buy_price:
            amount_to_spend = cash_balance * (self.config['buy_percentage'] / 100.0)
            amount_to_buy = amount_to_spend / current_price
            #buy_result = self.calculator.buy_limit_order(coin, current_price, amount_to_buy)
            buy_result = self.calculator.buy_limit_order(coin, current_price, 0.0001)
            print(f"Limit Buy Result: {buy_result}")

        # Functionality 2: Sell if current price >= avg buy price * (1 + threshold / 100)
        if current_price >= avg_buy_price * (1 + self.config['price_increase_threshold'] / 100.0):
            amount_to_sell = coin_balance * (self.config['sell_percentage'] / 100.0)
            #sell_result = self.calculator.sell_market_order(coin, amount_to_sell)
            sell_result = self.calculator.sell_market_order(coin, 0.0001)
            print(f"Market Sell Result: {sell_result}")

if __name__ == "__main__":
    # Load config
    with open("config.json") as config_file:
        config = json.load(config_file)

    access_key = config["access_key"]
    secret_key = config["secret_key"]

    calculator = AssetCalculator(access_key, secret_key)
    bot = TradingBot(calculator, config)
    bot.run()
