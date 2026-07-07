const mineflayer = require('mineflayer')
const { pathfinder, Movements, goals } = require('mineflayer-pathfinder')

const bot = mineflayer.createBot({
    host: 'pika.host',
    port: 25565,
    username: 'purelybot2'
})

bot.loadPlugin(pathfinder)

bot.once('spawn', () => {
    console.log('Bot joined the server!')
})

bot.on('messagestr', (message) => {
    console.log(message)
    if (message.includes('/register')) {
        console.log('Register prompt detected')
        bot.chat('/register abc123 abc123')
    }
    if (message.includes('/login')) {
        console.log('Login prompt detected')
        bot.chat('/login abc123')
    }
    if (message.includes('Right click the Server Selector to join a gamemode')){
        console.log("Opening game menu")
        bot.setQuickBarSlot(4)
        bot.activateItem()
    }
    if (message.includes("Welcome to PikaNetwork OpFactions!")){
        console.log("Entered Op Factions")
        const defaultMove = new Movements(bot)
        bot.pathfinder.setMovements(defaultMove)

        const x = 14
        const y = 87 
        const z = -1 

        // GoalBlock walks to the exact block
        bot.pathfinder.setGoal(new goals.GoalBlock(x, y, z))

    }
})

bot.on('goal_reached', () => {
  console.log('Arrived at destination!')
})

bot.on('windowOpen', async (window) => {
    if (window.title.value.includes('Server Selector')) {
        await bot.clickWindow(10, 0, 0);
        console.log("Clicked on Op factions")
    }
})

bot.on('chat', (username, message) => {
    // console.log(`<${username}> ${message}`)
})

bot.on('kicked', console.log)
bot.on('error', console.log)

