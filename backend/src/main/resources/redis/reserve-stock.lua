-- KEYS[1] = stock key, ARGV[1] = quantity to reserve
local stock = redis.call('GET', KEYS[1])
if stock == false then
  return -2
end
stock = tonumber(stock)
local qty = tonumber(ARGV[1])
if stock >= qty then
  redis.call('DECRBY', KEYS[1], qty)
  return stock - qty
end
return -1
